package com.yeso.backend.trip.application.course;

import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.TravelChain;
import com.yeso.backend.trip.domain.TravelChain.Stop;
import com.yeso.backend.trip.domain.TravelSource;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;
import com.yeso.backend.trip.infrastructure.RouteProperties;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.course.CourseResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.AttractionItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.DayResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.ItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.MealItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.WarningResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 커밋 뒤 자동차 이동값을 카카오모빌리티 도로 값으로 덮어쓴다(docs/api/trip.md 5장, #101).
 *
 * <p>카카오를 기다리는 동안 여행 잠금과 DB 연결을 잡지 않도록 1) 읽기 트랜잭션에서 지점을 읽고 2) 트랜잭션 밖에서 호출한
 * 뒤 3) 짧은 트랜잭션에서 여행을 잠그고 version이 그대로일 때만 쓴다. 쓰기는 항목의 이동 컬럼만 바꾸므로 여행 version이
 * 오르지 않는다. 어느 단계에서든 못 하면 이미 저장된 추정값을 그대로 둔다 — 요청은 실패하지 않는다.
 */
@Slf4j
@Service
public class CourseRouteRefresher {

    static final String ROUTE_TIME_ESTIMATED = "ROUTE_TIME_ESTIMATED";

    private final TripPlanRepository tripPlanRepository;
    private final CourseItemRepository courseItemRepository;
    private final CourseStorage courseStorage;
    private final KakaoMobilityClient client;
    private final RouteProperties properties;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;

    public CourseRouteRefresher(
            TripPlanRepository tripPlanRepository, CourseItemRepository courseItemRepository,
            CourseStorage courseStorage, KakaoMobilityClient client, RouteProperties properties,
            PlatformTransactionManager transactionManager) {
        this.tripPlanRepository = tripPlanRepository;
        this.courseItemRepository = courseItemRepository;
        this.courseStorage = courseStorage;
        this.client = client;
        this.properties = properties;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** 이동값. 항목 응답의 세 필드와 같다. */
    public record Travel(Integer minutes, Integer meters, TravelSource source) {
    }

    /** 5-1·5-3 응답에 도로 값을 반영해 돌려준다. 반영하지 못했으면 받은 응답 그대로다. */
    public CourseResponse refresh(CourseResponse response) {
        Map<String, Travel> travel = refresh(response.tripId(), response.version());
        return travel == null ? response : withTravel(response, travel);
    }

    /**
     * 도로 값을 저장한다(3-5는 응답에 코스가 없어 이것만 쓴다).
     *
     * @param expectedVersion 방금 커밋한 변경 뒤의 여행 version. 그사이 바뀌었으면 쓰지 않는다
     * @return 덮어썼으면 그 뒤 모든 항목의 이동값(항목 ID → 값), 아니면 null
     */
    public Map<String, Travel> refresh(Long tripId, int expectedVersion) {
        List<List<Stop<Long>>> requests = readOnlyTransaction.execute(status -> requests(tripId));
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        Map<Long, Leg> legs = callKakao(requests);
        if (legs.isEmpty()) {
            return null;
        }
        return writeTransaction.execute(status -> apply(tripId, expectedVersion, legs));
    }

    /** 자동차 여행에서 아직 도로 값이 아닌 구간이 있는 요청 묶음만. */
    private List<List<Stop<Long>>> requests(Long tripId) {
        TripPlan trip = tripPlanRepository.findById(tripId).orElse(null);
        if (trip == null || trip.getTransport() != Transport.CAR) {
            return List.of();
        }
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        Map<Long, TravelSource> sources = new HashMap<>();
        items.forEach(item -> sources.put(item.getId(), item.getTravelSource()));
        List<List<Stop<Long>>> requests = new ArrayList<>();
        for (List<Stop<Long>> day : courseStorage.stopsByDay(trip, items)) {
            for (List<Stop<Long>> request : TravelChain.requests(day)) {
                boolean stale = request.subList(1, request.size()).stream()
                        .anyMatch(stop -> sources.get(stop.key()) != TravelSource.ROAD);
                if (stale) {
                    requests.add(request);
                }
            }
        }
        return requests;
    }

    /** 트랜잭션 밖. 시간 상한을 넘기면 남은 묶음은 부르지 않는다. 도착 항목 id → 구간 값. */
    private Map<Long, Leg> callKakao(List<List<Stop<Long>>> requests) {
        long deadline = System.nanoTime() + properties.getTotalBudgetMillis() * 1_000_000L;
        Map<Long, Leg> legs = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            if (System.nanoTime() >= deadline) {
                log.warn("Road route time budget exhausted; skipped requests={}", requests.size() - i);
                break;
            }
            List<Stop<Long>> request = requests.get(i);
            Optional<List<Leg>> result = client.route(request.stream()
                    .map(stop -> new Point(stop.lat(), stop.lng())).toList());
            if (result.isEmpty()) {
                continue;
            }
            for (int k = 1; k < request.size(); k++) {
                legs.put(request.get(k).key(), result.get().get(k - 1));
            }
        }
        return legs;
    }

    private Map<String, Travel> apply(Long tripId, int expectedVersion, Map<Long, Leg> legs) {
        TripPlan trip = tripPlanRepository.lockById(tripId).orElse(null);
        if (trip == null || trip.getVersion() != expectedVersion || trip.getTransport() != Transport.CAR) {
            log.debug("Road route skipped: trip changed while routing (trip={})", tripId);
            return null;
        }
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        Map<String, Travel> travel = new HashMap<>();
        for (CourseItem item : items) {
            Leg leg = legs.get(item.getId());
            if (leg != null) {
                item.updateTravel(Math.max(1, (int) Math.round(leg.durationSeconds() / 60.0)), leg.distanceMeters(),
                        TravelSource.ROAD);
            }
            travel.put(item.itemId(), new Travel(item.getTravelMinutesFromPrevious(), item.getTravelDistanceMeters(),
                    item.getTravelSource()));
        }
        return travel;
    }

    /** 응답에서 이동 필드와 {@code ROUTE_TIME_ESTIMATED} 경고만 바꾼다(생성 경고 등 나머지는 그대로). */
    static CourseResponse withTravel(CourseResponse response, Map<String, Travel> travel) {
        List<DayResponse> days = response.days().stream()
                .map(day -> new DayResponse(day.dayIndex(), day.date(),
                        day.items().stream().map(item -> withTravel(item, travel)).toList()))
                .toList();
        List<WarningResponse> warnings = new ArrayList<>(response.warnings().stream()
                .filter(warning -> !ROUTE_TIME_ESTIMATED.equals(warning.code())).toList());
        if (travel.values().stream().anyMatch(t -> t.source() == TravelSource.ESTIMATE)) {
            warnings.add(0, new WarningResponse(ROUTE_TIME_ESTIMATED, null, null));
        }
        return new CourseResponse(
                response.tripId(), response.version(), response.myRole(), response.regionSigCd(),
                response.regionName(), response.startDate(), response.endDate(), response.scheduleDensity(),
                response.title(), response.titleSource(), response.recommendationMode(), response.tasteBasis(),
                days, warnings, response.updatedBy(), response.updatedAt());
    }

    private static ItemResponse withTravel(ItemResponse item, Map<String, Travel> travel) {
        return switch (item) {
            case AttractionItemResponse a -> {
                Travel t = travel.getOrDefault(a.itemId(), new Travel(null, null, null));
                yield new AttractionItemResponse(a.itemId(), a.type(), a.attractionId(), a.name(), a.category(),
                        a.thumbnailUrl(), a.address(), a.lat(), a.lng(), a.durationMinutes(), t.minutes(), t.meters(),
                        t.source() == null ? null : t.source().name(), a.estimated(), a.source(), a.reason());
            }
            case MealItemResponse m -> {
                Travel t = travel.getOrDefault(m.itemId(), new Travel(null, null, null));
                yield new MealItemResponse(m.itemId(), m.type(), m.meal(), m.durationMinutes(), t.minutes(),
                        t.meters(), t.source() == null ? null : t.source().name(), m.restaurant());
            }
        };
    }
}
