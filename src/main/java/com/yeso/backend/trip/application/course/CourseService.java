package com.yeso.backend.trip.application.course;

import com.yeso.backend.trip.application.RecommendationImpressionLogger;
import com.yeso.backend.attraction.application.ranking.DiaryFeedback;
import com.yeso.backend.attraction.application.ranking.TasteRanker;
import com.yeso.backend.trip.infrastructure.DiaryTasteSignalRepository;
import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.CourseMaterialService.ApprovedSummary;
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
import com.yeso.backend.trip.domain.CourseItemOrigin;
import com.yeso.backend.trip.domain.CourseItemSource;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.CourseRemoval;
import com.yeso.backend.trip.domain.CourseTitleSource;
import com.yeso.backend.trip.domain.InvalidScheduleDensityException;
import com.yeso.backend.trip.domain.InvalidTasteModeException;
import com.yeso.backend.trip.domain.RecommendationMode;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripRegionNotSelectedException;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
import com.yeso.backend.trip.infrastructure.CourseRemovalRepository;
import com.yeso.backend.trip.infrastructure.KakaoMapRouteClient;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient;
import com.yeso.backend.trip.infrastructure.RouteLegCache;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.course.CourseResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.AttractionItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.DayResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.ItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.MealItemResponse;
import com.yeso.backend.trip.presentation.course.CourseResponse.Travel;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final CourseRemovalRepository courseRemovalRepository;
    private final RouteLegCache routeLegCache;
    private final CourseStorage courseStorage;
    private final RegionEligibilityService regionEligibilityService;
    private final CourseMaterialService courseMaterialService;
    private final OnboardingQueryService onboardingQueryService;
    private final PreferenceService preferenceService;
    private final CourseGenerator courseGenerator;
    private final RestaurantQueryService restaurantQueryService;
    private final EntityManager entityManager;
    private final RecommendationImpressionLogger impressionLogger;
    private final TasteRanker tasteRanker;
    private final DiaryTasteSignalRepository diaryTasteSignalRepository;
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

        TasteEvidence evidence = randomOnly ? TasteEvidence.NONE : tasteEvidence(userId);
        Map<Long, Double> rankerScores = requesterVector == null ? Map.of()
                : onboardingQueryService.findLatestTasteAnswers(userId)
                        .flatMap(answers -> tasteRanker.score(
                                new TasteRanker.Survey(answers.travelStyles(), answers.travelMotives()), requesterVector,
                                candidates.stream().map(CourseCandidate::attractionId).toList()))
                        .orElse(Map.of());
        List<DiaryFeedback.Trip> diaryTrips = rankerScores.isEmpty() || !tasteRanker.diaryFeedbackEnabled()
                ? List.of() : diaryFeedbackTrips(userId);
        // 오프라인에서 이득이 확인되지 않아(ai reports/diary_feedback_v1.md) 회원을 무작위로 나눠 반영 여부를 비교한다.
        boolean diaryTreated = !diaryTrips.isEmpty() && tasteRanker.diaryFeedbackTreated(userId);
        if (diaryTreated) {
            rankerScores = tasteRanker.applyDiaryFeedback(rankerScores, diaryTrips);
        }

        Result result = courseGenerator.generate(new CourseGeneration.Request(
                region.getCity(), trip.getNights() + 1, density, trip.getTransport(), candidates,
                requesterVector, attractionVectors, officialCourses, randomOnly, evidence, rankerScores),
                new SplittableRandom());

        courseItemRepository.deleteByTripPlanId(tripId);
        courseRemovalRepository.deleteByTripPlanId(tripId); // 새 기준점: 이전 변경 기록을 지운다(D11-3)
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
        // 생성기 이동시간은 식사를 건너뛴 값이다. 식당 자동 배정까지 끝났으니 식당을 지점으로 넣는 저장 규칙으로 다시 매긴다.
        courseStorage.recalculateTravel(trip, courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId));

        trip.setScheduleDensity(density);
        trip.setTitle(result.title());
        trip.setTitleSource(CourseTitleSource.RULE);
        trip.setRecommendationMode(result.mode());
        trip.setTasteBasisUser(requester);
        trip.setCourseUpdatedBy(requester);
        trip.setCourseUpdatedAt(now);
        trip.setCourseBaselineAt(now);
        trip.setCourseIntro(null);
        trip.setCourseIntroSource(null);
        if (trip.getCourseFirstGeneratedAt() == null) {
            trip.setCourseFirstGeneratedAt(now);
        }
        entityManager.flush(); // 여행 버전을 올린 값으로 응답한다
        String rankerVersion = rankerScores.isEmpty() ? null : tasteRanker.version()
                .map(v -> diaryTrips.isEmpty() ? v
                        : v + "+" + DiaryFeedback.VERSION + (diaryTreated ? "" : "-control") + "(n=" + diaryTrips.size() + ")")
                .orElse(null);
        recordImpression(userId, tripId, result, rankerVersion);

        List<Warning> densityWarnings = result.warnings().stream()
                .filter(warning -> warning.code().equals("DENSITY_TARGET_NOT_MET"))
                .toList();
        return view(trip, Viewer.participant(userId), densityWarnings);
    }

    /** 최근 발행한 여행기 최대 {@value #MAX_DIARY_TRIPS}개와 그 여행의 최종 코스 관광지(§5 여행기 피드백). */
    private List<DiaryFeedback.Trip> diaryFeedbackTrips(Long userId) {
        Map<Long, Short> satisfaction = new java.util.LinkedHashMap<>();
        Map<Long, List<Long>> places = new java.util.LinkedHashMap<>();
        for (DiaryTasteSignalRepository.FeedbackRow row : diaryTasteSignalRepository.findFeedbackRows(userId)) {
            if (!places.containsKey(row.getDiaryId()) && places.size() == MAX_DIARY_TRIPS) {
                continue;
            }
            satisfaction.putIfAbsent(row.getDiaryId(), row.getSatisfaction());
            places.computeIfAbsent(row.getDiaryId(), id -> new java.util.ArrayList<>()).add(row.getAttractionId());
        }
        return places.entrySet().stream()
                .map(e -> new DiaryFeedback.Trip(satisfaction.get(e.getKey()), List.copyOf(e.getValue())))
                .toList();
    }

    private static final int MAX_DIARY_TRIPS = 10;

    /** 추첨 후보와 뽑힐 확률을 노출 기록으로 남긴다(docs/design/recommendation.md §9). 커밋 후 별도로 쓴다. */
    private void recordImpression(Long userId, Long tripId, Result result, String rankerVersion) {
        CourseGeneration.Exposure exposure = result.exposure();
        if (exposure == null || exposure.picks().isEmpty()) {
            return;
        }
        double total = exposure.pool().stream().mapToDouble(CourseGeneration.PoolEntry::weight).sum();
        List<RecommendationImpressionLogger.Candidate> candidates = exposure.pool().stream()
                .map(e -> new RecommendationImpressionLogger.Candidate(e.attractionId(), e.score(), e.weight(),
                        total > 0 ? e.weight() / total : 0))
                .toList();
        List<RecommendationImpressionLogger.Chosen> chosen = exposure.picks().stream()
                .map(pick -> new RecommendationImpressionLogger.Chosen(pick.attractionId(), pick.step(), pick.probability()))
                .toList();
        impressionLogger.record(userId, tripId, RecommendationImpressionLogger.COURSE_GENERATION, result.mode().name(),
                List.of(), rankerVersion, candidates, chosen);
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
        return view(trip, Viewer.participant(userId), List.of());
    }

    // ---------- 4-13 ----------

    /** 공유 링크 열람자용. 코스가 없으면 {@code days: []}다. */
    @Transactional(readOnly = true)
    public CourseResponse sharedView(TripPlan trip) {
        return view(trip, Viewer.shareLink(), List.of());
    }

    // ---------- 4-16 ----------

    /** 초대 링크로 들어온 사람의 읽기 전용 둘러보기. 4-13과 같은 모양이지만 참여자 이름을 보여 준다(D8·D9). */
    @Transactional(readOnly = true)
    public CourseResponse inviteView(TripPlan trip) {
        return view(trip, Viewer.invitee(), List.of());
    }

    /** 다른 유스케이스(편집·AI 소개 반영)가 응답을 만들 때 쓴다. */
    @Transactional(readOnly = true)
    public CourseResponse participantView(TripPlan trip, Long userId) {
        return view(trip, Viewer.participant(userId), List.of());
    }

    // ---------- helpers ----------

    /** 요청 → 요청자 프로필 설정(2-11, 기본 TASTE). */
    /** 요청자 최신 설문에서 추천 이유 근거를 만든다. 설문 전이면 근거 없음. */
    private TasteEvidence tasteEvidence(Long userId) {
        return onboardingQueryService.findLatestTasteAnswers(userId)
                .map(answers -> TasteEvidence.from(answers.travelStyles(), answers.travelMotives()))
                .orElse(TasteEvidence.NONE);
    }

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

    /** 누가 보는지. 공유 링크 열람자는 이름을 숨기고, 초대 링크 둘러보기는 이름을 보여 준다(D8). */
    record Viewer(String role, Long userId, boolean showNames) {
        static Viewer participant(Long userId) {
            return new Viewer(CourseResponse.PARTICIPANT, userId, true);
        }

        static Viewer shareLink() {
            return new Viewer(CourseResponse.VIEWER, null, false);
        }

        static Viewer invitee() {
            return new Viewer(CourseResponse.VIEWER, null, true);
        }
    }

    /**
     * 저장된 코스를 응답으로 바꾼다. 관광지 정보는 지금 데이터로 읽고(2026-09-27), 추천 대상에서 빠진 곳에는 경고를 붙인다.
     * {@code DENSITY_TARGET_NOT_MET}은 생성 결과에만 있는 경고라 생성 응답에만 싣는다.
     */
    CourseResponse view(TripPlan trip, Viewer viewer, List<Warning> generationWarnings) {
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(trip.getId());
        List<Long> attractionIds = items.stream().filter(item -> !item.isMeal()).map(CourseItem::getAttractionId).toList();
        Map<Long, AttractionView> attractions = courseMaterialService.findAttractionViews(attractionIds);
        Map<Long, ApprovedSummary> summaries = courseMaterialService.findApprovedSummaries(attractionIds);
        Map<Long, CourseMealRestaurant> restaurants = courseMealRestaurantRepository.findByCourseItemIdIn(items.stream()
                        .filter(CourseItem::isMeal).map(CourseItem::getId).toList()).stream()
                .collect(Collectors.toMap(CourseMealRestaurant::getCourseItemId, Function.identity()));
        boolean participant = CourseResponse.PARTICIPANT.equals(viewer.role());
        boolean tasteOwner = participant && trip.getTasteBasisUser() != null
                && trip.getTasteBasisUser().getId().equals(viewer.userId());

        List<WarningResponse> warnings = new ArrayList<>();
        List<DayResponse> days = new ArrayList<>();
        boolean llmReason = false;
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
                List<CourseItem> itemsOfDay = byDay.getOrDefault(dayIndex, List.of());
                Map<CourseItem, TravelLegs.Leg> legs = TravelLegs.of(itemsOfDay, attractions, restaurants);
                for (CourseItem item : itemsOfDay) {
                    Travel travel = travel(item, legs);
                    if (item.isMeal()) {
                        dayItems.add(new MealItemResponse(item.itemId(), "MEAL", item.getMealType().name(),
                                item.getStayMinutes(), item.getTravelMinutesFromPrevious(),
                                travel == null || item.getTravelMinutesFromPrevious() == null ? null : travel.meters(),
                                travel, snapshot(restaurants.get(item.getId()))));
                        continue;
                    }
                    AttractionView attraction = attractions.get(item.getAttractionId());
                    if (attraction == null || !attraction.recommendable()) {
                        warnings.add(new WarningResponse("ATTRACTION_NO_LONGER_RECOMMENDABLE", dayIndex, item.itemId()));
                    }
                    ApprovedSummary summary = summaries.get(item.getAttractionId());
                    llmReason |= "LLM".equals(item.getReasonSource());
                    dayItems.add(new AttractionItemResponse(
                            item.itemId(), "ATTRACTION", item.getAttractionId(),
                            attraction == null ? null : attraction.name(),
                            attraction == null ? null : attraction.category().name(),
                            attraction == null ? null : attraction.thumbnailUrl(),
                            attraction == null ? null : attraction.address(),
                            attraction == null ? null : attraction.lat(),
                            attraction == null ? null : attraction.lng(),
                            item.getStayMinutes(), item.getTravelMinutesFromPrevious(),
                            travel == null || item.getTravelMinutesFromPrevious() == null ? null : travel.meters(),
                            travel, true, item.getSource().name(), item.getReason(), item.getReasonSource(),
                            tasteOwner ? item.getPersonalReason() : null,
                            summary == null ? null : summary.oneLine(),
                            summary == null ? null : summary.basis(),
                            change(item, viewer)));
                }
                days.add(new DayResponse(dayIndex, trip.getStartDate().plusDays(dayIndex), dayItems));
            }
        }

        boolean hasCourse = !items.isEmpty();
        boolean hideNames = !viewer.showNames();
        Region region = trip.getRegion();
        String titleSource = hasCourse && trip.getTitleSource() != null ? trip.getTitleSource().name() : null;
        String introSource = hasCourse ? trip.getCourseIntroSource() : null;
        boolean aiGenerated = hasCourse && ("LLM".equals(titleSource) || "LLM".equals(introSource) || llmReason);
        return new CourseResponse(
                trip.getId(), trip.getVersion(), viewer.role(),
                region == null ? null : region.getSigCd(),
                region == null ? null : region.getProvince() + " " + region.getCity(),
                trip.getStartDate(), trip.getEndDate(), trip.getScheduleDensity(),
                hasCourse ? trip.getTitle() : null, titleSource,
                hasCourse ? trip.getCourseIntro() : null, introSource, aiGenerated,
                hasCourse && trip.getRecommendationMode() != null ? trip.getRecommendationMode().name() : null,
                hideNames || !hasCourse ? null : userRef(trip.getTasteBasisUser()),
                days, warnings,
                hasCourse ? changes(trip, items, viewer) : null,
                hideNames || !hasCourse ? null : userRef(trip.getCourseUpdatedBy()),
                hasCourse ? trip.getCourseUpdatedAt() : null);
    }

    /**
     * 앞 지점에서의 수단별 이동(D5). 5-7 지도 경로가 기억해 둔 실제 경로가 있으면 그 시간(자동차 ROAD, 도보·대중교통 ROUTE),
     * 없으면 직선 추정(STRAIGHT)이다. 코스 조회에서 경로를 새로 부르지는 않는다(편집 응답이 느려지지 않게).
     */
    private Travel travel(CourseItem item, Map<CourseItem, TravelLegs.Leg> legs) {
        TravelLegs.Leg leg = legs.get(item);
        if (leg == null) {
            return null;
        }
        KakaoMobilityClient.Point from = new KakaoMobilityClient.Point(leg.from().lat(), leg.from().lng());
        KakaoMobilityClient.Point to = new KakaoMobilityClient.Point(leg.to().lat(), leg.to().lng());
        Optional<KakaoMobilityClient.Leg> road = routeLegCache.get(RouteLegCache.key(from, to));
        Optional<KakaoMobilityClient.Leg> walk = routeLegCache.get(RouteLegCache.key("WALK", from, to));
        Optional<KakaoMapRouteClient.TransitLeg> transit = routeLegCache.getTransit(RouteLegCache.key("PUBLIC_TRANSIT", from, to));
        String carSource = road.isPresent() ? "ROAD" : "STRAIGHT";
        String walkSource = walk.isPresent() ? "ROUTE" : "STRAIGHT";
        String transitSource = transit.isPresent() ? "ROUTE" : "STRAIGHT";
        return new Travel(leg.straightMeters(),
                walk.map(found -> minutes(found.durationSeconds())).orElse(leg.minutes(Transport.WALK)),
                road.map(found -> minutes(found.durationSeconds())).orElse(leg.minutes(Transport.CAR)),
                transit.map(found -> minutes(found.leg().durationSeconds())).orElse(leg.minutes(Transport.PUBLIC_TRANSIT)),
                carSource, walkSource, transitSource,
                transit.map(KakaoMapRouteClient.TransitLeg::transfers).orElse(null),
                transit.map(KakaoMapRouteClient.TransitLeg::fare).orElse(null),
                "STRAIGHT".equals(carSource) || "STRAIGHT".equals(walkSource) || "STRAIGHT".equals(transitSource));
    }

    private static int minutes(int seconds) {
        return Math.max(1, (int) Math.round(seconds / 60.0));
    }

    private static CourseResponse.ItemChange change(CourseItem item, Viewer viewer) {
        CourseItemOrigin origin = item.getOrigin() == null ? CourseItemOrigin.BASE : item.getOrigin();
        UserRef by = !viewer.showNames() || item.getChangedByNickname() == null ? null
                : new UserRef(item.getChangedBy() == null ? null : item.getChangedBy().getId(), item.getChangedByNickname());
        CourseResponse.ReplacedFrom replacedFrom = item.getReplacedFromName() == null ? null
                : new CourseResponse.ReplacedFrom(item.getReplacedFromAttractionId(), item.getReplacedFromName());
        return new CourseResponse.ItemChange(origin.name(), by, replacedFrom, item.getChangeNote(), item.getChangedAt());
    }

    /** 일자별 추가·교체·제외 수, 되살릴 수 있는 제외 목록, 가장 최근 변경 한 건. */
    private CourseResponse.Changes changes(TripPlan trip, List<CourseItem> items, Viewer viewer) {
        List<CourseRemoval> removals = courseRemovalRepository.findByTripPlanIdAndRestoredAtIsNullOrderByRemovedAtDescIdDesc(trip.getId());
        List<CourseResponse.DayChange> dayChanges = new ArrayList<>();
        for (int dayIndex = 0; dayIndex <= trip.getNights(); dayIndex++) {
            int day = dayIndex;
            int added = (int) items.stream().filter(item -> item.getDayIndex() == day && item.getOrigin() == CourseItemOrigin.ADDED).count();
            int replaced = (int) items.stream().filter(item -> item.getDayIndex() == day && item.getOrigin() == CourseItemOrigin.REPLACED).count();
            int removed = (int) removals.stream().filter(removal -> removal.getDayIndex() == day).count();
            dayChanges.add(new CourseResponse.DayChange(day, added, replaced, removed));
        }
        List<CourseResponse.Removal> removed = removals.stream()
                .map(removal -> new CourseResponse.Removal(removal.getId(), removal.getAttractionId(), removal.getAttractionName(),
                        removal.getDayIndex(), removal.getOrigin().name(),
                        viewer.showNames() && removal.getRemovedByNickname() != null
                                ? new UserRef(removal.getRemovedBy() == null ? null : removal.getRemovedBy().getId(), removal.getRemovedByNickname())
                                : null,
                        removal.getNote(), removal.getRemovedAt()))
                .toList();

        CourseResponse.LatestChange latest = null;
        CourseItem latestItem = items.stream()
                .filter(item -> !item.isMeal() && item.getChangedAt() != null)
                .max(Comparator.comparing(CourseItem::getChangedAt).thenComparing(CourseItem::getId)).orElse(null);
        if (latestItem != null) {
            String type = switch (latestItem.getOrigin()) {
                case ADDED -> "ADDED";
                case REPLACED -> "REPLACED";
                case BASE -> "RESTORED";
            };
            CourseResponse.ItemChange change = change(latestItem, viewer);
            latest = new CourseResponse.LatestChange(type, latestItem.getDayIndex(), attractionName(latestItem),
                    latestItem.getReplacedFromName(), change.by(), latestItem.getChangeNote(), latestItem.getChangedAt());
        }
        if (!removed.isEmpty() && (latest == null || removed.get(0).removedAt().isAfter(latest.at()))) {
            CourseResponse.Removal last = removed.get(0);
            latest = new CourseResponse.LatestChange("REMOVED", last.dayIndex(), last.name(), null, last.removedBy(),
                    last.note(), last.removedAt());
        }
        return new CourseResponse.Changes(trip.getCourseBaselineAt(), dayChanges, removed, latest);
    }

    private String attractionName(CourseItem item) {
        AttractionView view = courseMaterialService.findAttractionViews(List.of(item.getAttractionId())).get(item.getAttractionId());
        return view == null ? null : view.name();
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
