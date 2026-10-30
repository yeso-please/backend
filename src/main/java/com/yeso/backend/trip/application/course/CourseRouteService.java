package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.CourseMaterialService.AttractionView;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.CourseInvalidQueryException;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
import com.yeso.backend.trip.infrastructure.KakaoMapRouteClient;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;
import com.yeso.backend.trip.infrastructure.RouteLegCache;
import com.yeso.backend.trip.infrastructure.RouteProperties;
import com.yeso.backend.trip.domain.TravelTimeEstimator;
import com.yeso.backend.trip.presentation.course.CourseFirstLegResponse;
import com.yeso.backend.trip.presentation.course.CourseRoutesResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 5-7 하루 코스의 지도 경로. 자동차는 카카오모빌리티 길찾기, 도보·대중교통은 카카오맵 경로 조회(2026-10-16 Q8)로 구간마다
 * 실제 경로·거리·시간을 받아 내려준다.
 *
 * <p>코스 편집·저장과 분리돼 있다. 편집 응답은 지금처럼 추정값으로 바로 나가고, 지도를 열 때만 이 API가 길찾기를 한다.
 * 카카오를 기다리는 동안 DB 연결을 잡지 않도록 지점만 읽기 트랜잭션에서 읽고 호출은 트랜잭션 밖에서 한다. 구간 결과는
 * 좌표 키로 서버 메모리에 기억해서({@link RouteLegCache}), 편집으로 바뀌지 않은 구간은 다시 부르지 않는다. 길찾기를 못 한
 * 구간은 직선으로 내려주고 요청은 실패시키지 않는다.
 */
@Slf4j
@Service
public class CourseRouteService {

    /** 카카오 길찾기 한 요청의 최대 지점 수(출발 + 경유 5 + 도착). */
    static final int MAX_POINTS_PER_REQUEST = 7;

    /** 길찾기는 출발·도착이 5m 안이면 경로를 거절한다. 여유를 두고 이 거리 안의 구간은 부르지 않고 직선으로 둔다. */
    static final double MIN_ROUTABLE_KM = 0.010;

    private final TripService tripService;
    private final CourseItemRepository courseItemRepository;
    private final CourseMealRestaurantRepository courseMealRestaurantRepository;
    private final CourseMaterialService courseMaterialService;
    private final KakaoMobilityClient client;
    private final KakaoMapRouteClient mapClient;
    private final RouteLegCache cache;
    private final RouteProperties properties;
    private final TransactionTemplate readOnlyTransaction;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CourseRouteService(
            TripService tripService, CourseItemRepository courseItemRepository,
            CourseMealRestaurantRepository courseMealRestaurantRepository, CourseMaterialService courseMaterialService,
            KakaoMobilityClient client, KakaoMapRouteClient mapClient, RouteLegCache cache, RouteProperties properties,
            PlatformTransactionManager transactionManager) {
        this.tripService = tripService;
        this.courseItemRepository = courseItemRepository;
        this.courseMealRestaurantRepository = courseMealRestaurantRepository;
        this.courseMaterialService = courseMaterialService;
        this.client = client;
        this.mapClient = mapClient;
        this.cache = cache;
        this.properties = properties;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record Planned(String fromItemId, String toItemId, Point from, Point to) {
        String key() {
            return RouteLegCache.key(from, to);
        }

        String key(String mode) {
            return RouteLegCache.key(mode, from, to);
        }
    }

    private record DayPlan(Long tripId, int version, Transport transport, List<Planned> legs) {
    }

    /**
     * @param transportOverride 지도에서 다른 수단의 경로를 볼 때(`WALK` | `CAR` | `PUBLIC_TRANSIT`). null이면 여행 이동수단
     */
    public CourseRoutesResponse routes(Long userId, Long tripId, int dayIndex, String transportOverride) {
        Transport requested = parseTransport(transportOverride);
        DayPlan plan = readOnlyTransaction.execute(status -> plan(userId, tripId, dayIndex));
        Transport transport = requested == null ? plan.transport() : requested;
        List<CourseRoutesResponse.Leg> legs = switch (transport) {
            case CAR -> {
                Map<String, Leg> road = roadLegs(plan.legs());
                yield plan.legs().stream().map(planned -> toResponse(planned, road.get(planned.key()), "ROAD", null, transport)).toList();
            }
            case WALK -> {
                Map<String, KakaoMapRouteClient.TransitLeg> walk = perLeg(plan.legs(), "WALK",
                        leg -> mapClient.walk(leg.from(), leg.to()).map(found -> new KakaoMapRouteClient.TransitLeg(found, 0, null)));
                yield plan.legs().stream().map(planned -> toResponse(planned,
                        walk.containsKey(planned.key("WALK")) ? walk.get(planned.key("WALK")).leg() : null, "WALK", null, transport)).toList();
            }
            case PUBLIC_TRANSIT -> {
                Map<String, KakaoMapRouteClient.TransitLeg> transit = perLeg(plan.legs(), "PUBLIC_TRANSIT",
                        leg -> mapClient.transit(leg.from(), leg.to()));
                yield plan.legs().stream().map(planned -> {
                    KakaoMapRouteClient.TransitLeg found = transit.get(planned.key("PUBLIC_TRANSIT"));
                    return toResponse(planned, found == null ? null : found.leg(), "TRANSIT", found, transport);
                }).toList();
            }
        };
        return new CourseRoutesResponse(plan.tripId(), plan.version(), dayIndex, transport.name(), legs);
    }

    /** 도보는 이 직선거리(km)까지만 계산한다. 더 멀면 카카오를 부르지 않고 null. */
    static final double MAX_WALK_KM = 10.0;

    private record FirstStop(Long tripId, String itemId, Long attractionId, String name, Point point) {
    }

    /**
     * 5-8 지금 위치에서 그날 첫 관광지까지 자동차·도보·대중교통. 위치는 저장하지 않고, 기억(캐시) 키도 약 100m로 반올림한
     * 좌표만 쓴다. 세 수단을 동시에 부르고 요청 전체 시간 상한을 넘기거나 실패한 수단은 직선 추정이다.
     */
    public CourseFirstLegResponse firstLeg(Long userId, Long tripId, int dayIndex, double lat, double lng) {
        if (lat < 33 || lat > 39 || lng < 124 || lng > 132) {
            throw new CourseInvalidQueryException("현재 위치가 국내 범위(위도 33~39, 경도 124~132) 밖입니다");
        }
        FirstStop stop = readOnlyTransaction.execute(status -> firstStop(userId, tripId, dayIndex));
        Point origin = new Point(Math.round(lat * 1000) / 1000.0, Math.round(lng * 1000) / 1000.0);
        Planned leg = new Planned("here", stop.itemId(), origin, stop.point());
        double km = TravelTimeEstimator.distanceKm(origin.lat(), origin.lng(), stop.point().lat(), stop.point().lng());
        boolean routable = km >= MIN_ROUTABLE_KM;
        boolean walkable = km <= MAX_WALK_KM;

        Optional<Leg> cachedCar = cache.get(leg.key());
        Future<Optional<Leg>> car = cachedCar.isPresent() || !routable ? null
                : executor.submit(() -> client.route(List.of(origin, stop.point())).map(found -> found.get(0)));
        Optional<KakaoMapRouteClient.TransitLeg> cachedWalk = cache.getTransit(leg.key("WALK"));
        Future<Optional<KakaoMapRouteClient.TransitLeg>> walk = cachedWalk.isPresent() || !routable || !walkable ? null
                : executor.submit(() -> mapClient.walk(origin, stop.point())
                        .map(found -> new KakaoMapRouteClient.TransitLeg(found, 0, null)));
        Optional<KakaoMapRouteClient.TransitLeg> cachedTransit = cache.getTransit(leg.key("PUBLIC_TRANSIT"));
        Future<Optional<KakaoMapRouteClient.TransitLeg>> transit = cachedTransit.isPresent() || !routable ? null
                : executor.submit(() -> mapClient.transit(origin, stop.point()));

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getTotalBudgetMillis());
        Optional<Leg> carLeg = car == null ? cachedCar : await(car, deadline);
        if (car != null) carLeg.ifPresent(found -> cache.put(leg.key(), found));
        Optional<KakaoMapRouteClient.TransitLeg> walkLeg = walk == null ? cachedWalk : await(walk, deadline);
        if (walk != null) walkLeg.ifPresent(found -> cache.putTransit(leg.key("WALK"), found));
        Optional<KakaoMapRouteClient.TransitLeg> transitLeg = transit == null ? cachedTransit : await(transit, deadline);
        if (transit != null) transitLeg.ifPresent(found -> cache.putTransit(leg.key("PUBLIC_TRANSIT"), found));

        return new CourseFirstLegResponse(stop.tripId(), dayIndex, stop.itemId(), stop.attractionId(), stop.name(),
                stop.point().lat(), stop.point().lng(), (int) Math.round(km * 1000),
                mode(leg, carLeg.orElse(null), "ROAD", null, Transport.CAR),
                walkable ? mode(leg, walkLeg.map(KakaoMapRouteClient.TransitLeg::leg).orElse(null), "WALK", null, Transport.WALK) : null,
                mode(leg, transitLeg.map(KakaoMapRouteClient.TransitLeg::leg).orElse(null), "TRANSIT",
                        transitLeg.orElse(null), Transport.PUBLIC_TRANSIT));
    }

    private static CourseFirstLegResponse.Mode mode(Planned leg, Leg found, String source,
                                                    KakaoMapRouteClient.TransitLeg transit, Transport transport) {
        CourseRoutesResponse.Leg view = toResponse(leg, found, source, transit, transport);
        return new CourseFirstLegResponse.Mode(view.source(), view.distanceMeters(), view.durationMinutes(),
                view.transfers(), view.fare());
    }

    private FirstStop firstStop(Long userId, Long tripId, int dayIndex) {
        TripPlan trip = tripService.requireParticipantTrip(userId, tripId);
        if (dayIndex < 0 || dayIndex > trip.getNights()) {
            throw new CourseInvalidQueryException("dayIndex가 여행 날짜 범위 밖입니다: " + dayIndex);
        }
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        if (items.isEmpty()) {
            throw new CourseNotFoundException(tripId);
        }
        CourseItem first = items.stream()
                .filter(item -> item.getDayIndex() == dayIndex && !item.isMeal())
                .findFirst()
                .orElseThrow(() -> new CourseInvalidQueryException("그날 코스에 관광지가 없습니다: " + dayIndex));
        AttractionView view = courseMaterialService.findAttractionViews(List.of(first.getAttractionId()))
                .get(first.getAttractionId());
        if (view == null || view.lat() == null || view.lng() == null) {
            throw new CourseInvalidQueryException("첫 관광지의 좌표가 없습니다");
        }
        return new FirstStop(trip.getId(), first.itemId(), view.attractionId(), view.name(), new Point(view.lat(), view.lng()));
    }

    private static Transport parseTransport(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Transport.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new CourseInvalidQueryException("transport는 WALK, CAR, PUBLIC_TRANSIT 중 하나여야 합니다: " + value);
        }
    }

    /**
     * 도보·대중교통은 구간마다 따로 부른다(경유지가 없는 API). 기억해 둔 구간은 그대로 쓰고, 나머지를 동시에 부르되 요청 전체
     * 시간 상한({@code totalBudgetMillis})을 넘기면 남은 구간은 직선이다. 키는 {@code mode:좌표}다.
     */
    private Map<String, KakaoMapRouteClient.TransitLeg> perLeg(
            List<Planned> legs, String mode,
            java.util.function.Function<Planned, Optional<KakaoMapRouteClient.TransitLeg>> fetch) {
        Map<String, KakaoMapRouteClient.TransitLeg> found = new HashMap<>();
        List<Planned> calls = new ArrayList<>();
        for (Planned leg : legs) {
            String key = leg.key(mode);
            Optional<KakaoMapRouteClient.TransitLeg> cached = cache.getTransit(key);
            if (cached.isPresent()) {
                found.put(key, cached.get());
            } else if (!tooClose(leg) && calls.stream().noneMatch(call -> call.key(mode).equals(key))) {
                calls.add(leg);
            }
        }
        if (calls.isEmpty()) {
            return found;
        }
        List<Future<Optional<KakaoMapRouteClient.TransitLeg>>> futures = calls.stream()
                .map(leg -> executor.submit(() -> fetch.apply(leg)))
                .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getTotalBudgetMillis());
        for (int i = 0; i < calls.size(); i++) {
            Optional<KakaoMapRouteClient.TransitLeg> result = await(futures.get(i), deadline);
            if (result.isPresent()) {
                String key = calls.get(i).key(mode);
                found.put(key, result.get());
                cache.putTransit(key, result.get());
            }
        }
        return found;
    }

    private DayPlan plan(Long userId, Long tripId, int dayIndex) {
        TripPlan trip = tripService.requireParticipantTrip(userId, tripId);
        if (dayIndex < 0 || dayIndex > trip.getNights()) {
            throw new CourseInvalidQueryException("dayIndex가 여행 날짜 범위 밖입니다: " + dayIndex);
        }
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        if (items.isEmpty()) {
            throw new CourseNotFoundException(tripId);
        }
        List<CourseItem> day = items.stream().filter(item -> item.getDayIndex() == dayIndex).toList();
        Map<Long, AttractionView> attractions = courseMaterialService.findAttractionViews(day.stream()
                .filter(item -> !item.isMeal()).map(CourseItem::getAttractionId).toList());
        Map<Long, CourseMealRestaurant> restaurants = courseMealRestaurantRepository.findByCourseItemIdIn(day.stream()
                        .filter(CourseItem::isMeal).map(CourseItem::getId).toList()).stream()
                .collect(Collectors.toMap(CourseMealRestaurant::getCourseItemId, Function.identity()));
        List<Planned> legs = new ArrayList<>();
        TravelLegs.of(day, attractions, restaurants).forEach((to, leg) -> legs.add(new Planned(
                leg.fromItem().itemId(), to.itemId(),
                new Point(leg.from().lat(), leg.from().lng()), new Point(leg.to().lat(), leg.to().lng()))));
        return new DayPlan(trip.getId(), trip.getVersion(), trip.getTransport(), legs);
    }

    /** 구간 키 → 도로 구간. 기억해 둔 구간은 그대로 쓰고, 나머지만 이어진 묶음으로 동시에 길찾기한다. */
    private Map<String, Leg> roadLegs(List<Planned> legs) {
        Map<String, Leg> road = new HashMap<>();
        List<List<Planned>> requests = new ArrayList<>();
        List<Planned> current = new ArrayList<>();
        for (Planned leg : legs) {
            Optional<Leg> cached = cache.get(leg.key());
            if (cached.isPresent()) {
                road.put(leg.key(), cached.get());
            }
            boolean call = cached.isEmpty() && !tooClose(leg);
            if (!call || current.size() == MAX_POINTS_PER_REQUEST - 1) {
                if (!current.isEmpty()) {
                    requests.add(current);
                }
                current = new ArrayList<>();
            }
            if (call) {
                current.add(leg);
            }
        }
        if (!current.isEmpty()) {
            requests.add(current);
        }
        if (requests.isEmpty()) {
            return road;
        }

        List<Future<Optional<List<Leg>>>> futures = requests.stream()
                .map(request -> executor.submit(() -> client.route(points(request))))
                .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getTotalBudgetMillis());
        for (int i = 0; i < requests.size(); i++) {
            Optional<List<Leg>> result = await(futures.get(i), deadline);
            if (result.isEmpty()) {
                continue;
            }
            List<Planned> request = requests.get(i);
            for (int k = 0; k < request.size(); k++) {
                Leg leg = result.get().get(k);
                road.put(request.get(k).key(), leg);
                cache.put(request.get(k).key(), leg);
            }
        }
        return road;
    }

    /** 묶음 안의 구간은 이어져 있다(앞 구간의 도착 = 다음 구간의 출발). 출발 + 각 구간의 도착이 요청 지점이다. */
    private static List<Point> points(List<Planned> request) {
        List<Point> points = new ArrayList<>();
        points.add(request.get(0).from());
        request.forEach(leg -> points.add(leg.to()));
        return points;
    }

    private static <T> Optional<T> await(Future<Optional<T>> future, long deadline) {
        long remaining = deadline - System.nanoTime();
        try {
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Road route time budget exhausted; leg group falls back to straight line");
        } catch (ExecutionException e) {
            log.warn("Road route failed: {}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private static boolean tooClose(Planned leg) {
        return TravelTimeEstimator.distanceKm(leg.from().lat(), leg.from().lng(), leg.to().lat(), leg.to().lng())
                < MIN_ROUTABLE_KM;
    }

    /**
     * @param source 실제 경로를 받았을 때의 출처(ROAD | WALK | TRANSIT). 못 받았으면 STRAIGHT
     */
    private static CourseRoutesResponse.Leg toResponse(Planned planned, Leg road, String source,
                                                       KakaoMapRouteClient.TransitLeg transit, Transport transport) {
        if (road != null) {
            List<double[]> path = road.path().size() >= 2
                    ? road.path().stream().map(p -> new double[]{p.lat(), p.lng()}).toList()
                    : List.of(new double[]{planned.from().lat(), planned.from().lng()}, new double[]{planned.to().lat(), planned.to().lng()});
            return new CourseRoutesResponse.Leg(planned.fromItemId(), planned.toItemId(), source,
                    road.distanceMeters(), Math.max(1, (int) Math.round(road.durationSeconds() / 60.0)), path,
                    transit == null ? null : transit.transfers(), transit == null ? null : transit.fare());
        }
        Point from = planned.from();
        Point to = planned.to();
        int meters = (int) Math.round(TravelTimeEstimator.distanceKm(from.lat(), from.lng(), to.lat(), to.lng()) * 1000);
        int minutes = TravelTimeEstimator.minutes(from.lat(), from.lng(), to.lat(), to.lng(), transport);
        return new CourseRoutesResponse.Leg(planned.fromItemId(), planned.toItemId(), "STRAIGHT", meters, minutes,
                List.of(new double[]{from.lat(), from.lng()}, new double[]{to.lat(), to.lng()}), null, null);
    }
}
