package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.CourseMaterialService.AttractionView;
import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RestaurantQueryService;
import com.yeso.backend.attraction.application.region.RestaurantQueryService.Candidate;
import com.yeso.backend.attraction.application.region.RestaurantQueryService.RegionRestaurants;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.domain.Attraction;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.profile.application.preference.PreferenceService;
import com.yeso.backend.profile.domain.CourseTasteMode;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.application.course.CourseGeneration.AttractionItem;
import com.yeso.backend.trip.application.course.CourseGeneration.Day;
import com.yeso.backend.trip.application.course.CourseGeneration.Item;
import com.yeso.backend.trip.application.course.CourseGeneration.OfficialCourse;
import com.yeso.backend.trip.application.course.CourseGeneration.Result;
import com.yeso.backend.trip.application.course.CourseGeneration.Warning;
import com.yeso.backend.trip.domain.CourseCreatorOnlyException;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseItemSource;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.CourseTitleSource;
import com.yeso.backend.trip.domain.InvalidScheduleDensityException;
import com.yeso.backend.trip.domain.InvalidTasteModeException;
import com.yeso.backend.trip.domain.RecommendationMode;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripRegionNotSelectedException;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.course.CourseResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.AttractionItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.DayResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.ItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.MealItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.UserRef;
import com.yeso.backend.trip.presentation.course.CourseResponse.WarningResponse;
import com.yeso.backend.trip.presentation.course.GenerateCourseRequest;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 코스 생성·조회(docs/api/trip.md 5-1·5-2)와 공유 코스 조회(4-13).
 * 코스 테이블과 {@code trip_plans}의 코스 정보는 한 트랜잭션에서 함께 바꾸고, 바꿀 때마다 여행 버전을 올린다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseService {

    private static final String RELAXED = "RELAXED";
    private static final int MEAL_RESTAURANT_RADIUS_METERS = 5_000;

    private final TripService tripService;
    private final TripPlanRepository tripPlanRepository;
    private final TripParticipantRepository tripParticipantRepository;
    private final CourseItemRepository courseItemRepository;
    private final CourseMealRestaurantRepository courseMealRestaurantRepository;
    private final RegionEligibilityService regionEligibilityService;
    private final CourseMaterialService courseMaterialService;
    private final OnboardingQueryService onboardingQueryService;
    private final PreferenceService preferenceService;
    private final CourseGenerator courseGenerator;
    private final RestaurantQueryService restaurantQueryService;
    private final EntityManager entityManager;
    private final Clock clock;

    // ---------- 5-1 ----------

    /**
     * 요청자 취향으로 코스를 만들거나 새로 만든다. 첫 생성은 여행을 만든 사람만(탈퇴했으면 누구나), 재생성은 참여자 누구나.
     * 재생성하면 기존 항목과 고른 식당이 사라진다. 여행 행을 잠가 동시 생성은 한 요청만 성공한다.
     */
    public CourseResponse generate(Long userId, Long tripId, GenerateCourseRequest request) {
        tripService.requireParticipantTrip(userId, tripId);
        TripPlan trip = tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        tripService.requireNotEnded(trip);
        if (request.version() != trip.getVersion()) {
            throw new TripVersionConflictException();
        }
        Region region = trip.getRegion();
        if (region == null) {
            throw new TripRegionNotSelectedException(tripId);
        }
        boolean creatorStillIn = tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, trip.getOwnerUser().getId());
        if (!trip.hasGeneratedCourse() && !trip.isCreatedBy(userId) && creatorStillIn) {
            throw new CourseCreatorOnlyException(tripId);
        }
        String density = resolveDensity(request.scheduleDensity(), trip, userId);
        boolean randomOnly = resolveTasteMode(request.tasteMode(), userId) == CourseTasteMode.RANDOM;

        List<CourseCandidate> candidates = regionEligibilityService.findCourseCandidates(region.getSigCd());
        float[] requesterVector = randomOnly ? null : onboardingQueryService.findTasteVector(userId).orElse(null);
        Map<Long, float[]> attractionVectors = requesterVector == null ? Map.of()
                : courseMaterialService.findAttractionVectors(candidates.stream().map(CourseCandidate::attractionId).toList());
        List<OfficialCourse> officialCourses = randomOnly ? List.of()
                : courseMaterialService.findOfficialCourses(region.getSigCd()).stream()
                        .map(course -> new OfficialCourse(course.title(), course.attractionIds()))
                        .toList();

        Result result = courseGenerator.generate(new CourseGeneration.Request(
                region.getCity(), trip.getNights() + 1, density, trip.getTransport(), candidates,
                requesterVector, attractionVectors, officialCourses, randomOnly), new SplittableRandom());

        courseItemRepository.deleteByTripPlanId(tripId);
        User requester = entityManager.getReference(User.class, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        MealAssigner mealAssigner = new MealAssigner(region, restaurantQueryService.loadTourApi(region.getSigCd()));
        for (Day day : result.days()) {
            int order = 0;
            CourseCandidate previousAttraction = null;
            for (Item item : day.items()) {
                CourseItem saved = courseItemRepository.save(switch (item) {
                    case AttractionItem attraction -> CourseItem.attraction(
                            trip, day.dayIndex(), order,
                            entityManager.getReference(Attraction.class, attraction.attraction().attractionId()),
                            attraction.attraction().stayMinutes(), attraction.travelMinutesFromPrevious(),
                            CourseItemSource.RECOMMEND, attraction.reason());
                    case CourseGeneration.MealItem meal -> CourseItem.meal(trip, day.dayIndex(), order, meal.mealType());
                });
                if (item instanceof AttractionItem attraction) {
                    previousAttraction = attraction.attraction();
                } else {
                    mealAssigner.assign(saved, previousAttraction, requester, now);
                }
                order++;
            }
        }

        trip.setScheduleDensity(density);
        trip.setTitle(result.title());
        trip.setTitleSource(CourseTitleSource.RULE);
        trip.setRecommendationMode(result.mode());
        trip.setTasteBasisUser(requester);
        trip.setCourseUpdatedBy(requester);
        trip.setCourseUpdatedAt(now);
        if (trip.getCourseFirstGeneratedAt() == null) {
            trip.setCourseFirstGeneratedAt(now);
        }
        entityManager.flush(); // 여행 버전을 올린 값으로 응답한다

        List<Warning> densityWarnings = result.warnings().stream()
                .filter(warning -> warning.code().equals("DENSITY_TARGET_NOT_MET"))
                .toList();
        return view(trip, CourseResponse.PARTICIPANT, densityWarnings);
    }

    /**
     * 식사 슬롯마다 TourAPI 식당을 자동 배정한다(5-1). 지역 식당은 생성당 한 번만 읽고, 기준점은 5-5와 같다
     * (그날 식사 앞 마지막 관광지, 없으면 지역 중심). 같은 코스에서 같은 식당은 한 번만 쓴다.
     */
    private final class MealAssigner {
        private final Region region;
        private final RegionRestaurants restaurants;
        private final MealRestaurantPicker picker = new MealRestaurantPicker();
        private final RandomGenerator random = new SplittableRandom();
        private final Set<String> used = new HashSet<>();

        MealAssigner(Region region, RegionRestaurants restaurants) {
            this.region = region;
            this.restaurants = restaurants;
        }

        void assign(CourseItem meal, CourseCandidate previousAttraction, User requester, LocalDateTime now) {
            double lat;
            double lng;
            if (previousAttraction != null) {
                lat = previousAttraction.lat();
                lng = previousAttraction.lng();
            } else if (region.getLat() != null && region.getLng() != null) {
                lat = region.getLat();
                lng = region.getLng();
            } else {
                return;
            }
            List<Candidate> nearest = restaurants.nearest(lat, lng, MEAL_RESTAURANT_RADIUS_METERS, Integer.MAX_VALUE);
            picker.pick(nearest, used, random).ifPresent(candidate -> {
                used.add(candidate.externalId());
                courseMealRestaurantRepository.save(CourseMealRestaurant.of(
                        meal, RestaurantService.tourApiSnapshot(candidate), requester, now));
            });
        }
    }

    // ---------- 5-2 ----------

    @Transactional(readOnly = true)
    public CourseResponse getCourse(Long userId, Long tripId) {
        TripPlan trip = tripService.requireParticipantTrip(userId, tripId);
        if (!courseItemRepository.existsByTripPlanId(tripId)) {
            throw new CourseNotFoundException(tripId);
        }
        return view(trip, CourseResponse.PARTICIPANT, List.of());
    }

    // ---------- 4-13 ----------

    /** 공유 링크 열람자용. 코스가 없으면 {@code days: []}다. */
    @Transactional(readOnly = true)
    public CourseResponse sharedView(TripPlan trip) {
        return view(trip, CourseResponse.VIEWER, List.of());
    }

    // ---------- helpers ----------

    /** 요청 → 요청자 프로필 설정(2-11, 기본 TASTE). */
    private CourseTasteMode resolveTasteMode(String requested, Long userId) {
        if (requested == null) {
            return preferenceService.courseTasteModeOf(userId);
        }
        if (!requested.equals("TASTE") && !requested.equals("RANDOM")) {
            throw new InvalidTasteModeException(requested);
        }
        return CourseTasteMode.valueOf(requested);
    }

    /** 요청 → 여행 밀도 → 요청자 온보딩 밀도 → RELAXED. */
    private String resolveDensity(String requested, TripPlan trip, Long userId) {
        if (requested != null) {
            if (!requested.equals("RELAXED") && !requested.equals("PACKED")) {
                throw new InvalidScheduleDensityException();
            }
            return requested;
        }
        if (trip.getScheduleDensity() != null) {
            return trip.getScheduleDensity();
        }
        return onboardingQueryService.findLatestScheduleDensity(userId).orElse(RELAXED);
    }

    /**
     * 저장된 코스를 응답으로 바꾼다. 관광지 정보는 지금 데이터로 읽고(2026-09-27), 추천 대상에서 빠진 곳에는 경고를 붙인다.
     * {@code DENSITY_TARGET_NOT_MET}은 생성 결과에만 있는 경고라 생성 응답에만 싣는다.
     */
    CourseResponse view(TripPlan trip, String role, List<Warning> generationWarnings) {
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(trip.getId());
        Map<Long, AttractionView> attractions = courseMaterialService.findAttractionViews(items.stream()
                .filter(item -> !item.isMeal()).map(CourseItem::getAttractionId).toList());
        Map<Long, CourseMealRestaurant> restaurants = courseMealRestaurantRepository.findByCourseItemIdIn(items.stream()
                        .filter(CourseItem::isMeal).map(CourseItem::getId).toList()).stream()
                .collect(Collectors.toMap(CourseMealRestaurant::getCourseItemId, Function.identity()));

        List<WarningResponse> warnings = new ArrayList<>();
        List<DayResponse> days = new ArrayList<>();
        if (!items.isEmpty()) {
            warnings.add(new WarningResponse("ROUTE_TIME_ESTIMATED", null, null));
            if (trip.getRecommendationMode() == RecommendationMode.TOUR_OFFICIAL
                    || trip.getRecommendationMode() == RecommendationMode.RULE_BASED) {
                warnings.add(new WarningResponse("PERSONALIZATION_FALLBACK", null, null));
            }
            generationWarnings.forEach(w -> warnings.add(new WarningResponse(w.code(), w.dayIndex(), null)));

            Map<Integer, List<CourseItem>> byDay = items.stream()
                    .collect(Collectors.groupingBy(CourseItem::getDayIndex));
            for (int dayIndex = 0; dayIndex <= trip.getNights(); dayIndex++) {
                List<ItemResponse> dayItems = new ArrayList<>();
                for (CourseItem item : byDay.getOrDefault(dayIndex, List.of())) {
                    if (item.isMeal()) {
                        dayItems.add(new MealItemResponse(item.itemId(), "MEAL", item.getMealType().name(),
                                item.getStayMinutes(), snapshot(restaurants.get(item.getId()))));
                        continue;
                    }
                    AttractionView attraction = attractions.get(item.getAttractionId());
                    if (attraction == null || !attraction.recommendable()) {
                        warnings.add(new WarningResponse("ATTRACTION_NO_LONGER_RECOMMENDABLE", dayIndex, item.itemId()));
                    }
                    dayItems.add(new AttractionItemResponse(
                            item.itemId(), "ATTRACTION", item.getAttractionId(),
                            attraction == null ? null : attraction.name(),
                            attraction == null ? null : attraction.category().name(),
                            attraction == null ? null : attraction.thumbnailUrl(),
                            attraction == null ? null : attraction.address(),
                            attraction == null ? null : attraction.lat(),
                            attraction == null ? null : attraction.lng(),
                            item.getStayMinutes(), item.getTravelMinutesFromPrevious(), true,
                            item.getSource().name(), item.getReason()));
                }
                days.add(new DayResponse(dayIndex, trip.getStartDate().plusDays(dayIndex), dayItems));
            }
        }

        boolean viewer = CourseResponse.VIEWER.equals(role);
        boolean hasCourse = !items.isEmpty();
        Region region = trip.getRegion();
        return new CourseResponse(
                trip.getId(), trip.getVersion(), role,
                region == null ? null : region.getSigCd(),
                region == null ? null : region.getProvince() + " " + region.getCity(),
                trip.getStartDate(), trip.getEndDate(), trip.getScheduleDensity(),
                hasCourse ? trip.getTitle() : null,
                hasCourse && trip.getTitleSource() != null ? trip.getTitleSource().name() : null,
                hasCourse && trip.getRecommendationMode() != null ? trip.getRecommendationMode().name() : null,
                viewer || !hasCourse ? null : userRef(trip.getTasteBasisUser()),
                days, warnings,
                viewer || !hasCourse ? null : userRef(trip.getCourseUpdatedBy()),
                hasCourse ? trip.getCourseUpdatedAt() : null);
    }

    private static UserRef userRef(User user) {
        return user == null ? null : new UserRef(user.getId(), user.getNickname());
    }

    private static RestaurantSnapshot snapshot(CourseMealRestaurant restaurant) {
        if (restaurant == null) {
            return null;
        }
        return new RestaurantSnapshot(
                restaurant.getProvider(), restaurant.getExternalId(), restaurant.getName(), restaurant.getCategory(),
                restaurant.getAddress(), restaurant.getRoadAddress(), restaurant.getLat(), restaurant.getLng(),
                restaurant.getPhone(), restaurant.getPlaceUrl(), restaurant.getImageUrl(),
                restaurant.getRepresentativeMenu(), restaurant.getEvidenceLabels(),
                restaurant.getSources().stream()
                        .map(source -> new RestaurantSnapshot.Source(source.name(), source.url(), parseTime(source.fetchedAt())))
                        .toList());
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
