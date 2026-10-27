package com.yeso.backend.trip.application.context;

import com.yeso.backend.attraction.application.ranking.TasteRanker;
import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.EligibleRegion;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.trip.application.RecommendationImpressionLogger;
import com.yeso.backend.trip.application.course.CourseStorage;
import com.yeso.backend.trip.domain.DrawCondition;
import com.yeso.backend.trip.domain.DrawIgnoredCondition;
import com.yeso.backend.trip.domain.DrawIgnoredReason;
import com.yeso.backend.trip.domain.DrawInvalidModeException;
import com.yeso.backend.trip.domain.DrawNoConditionSelectedException;
import com.yeso.backend.trip.domain.DrawNoEligibleRegionException;
import com.yeso.backend.trip.domain.DrawRegionNotEligibleException;
import com.yeso.backend.trip.domain.DrawSigCdRequiredException;
import com.yeso.backend.trip.domain.DrawWarning;
import com.yeso.backend.trip.domain.InvalidOriginException;
import com.yeso.backend.trip.domain.InvalidScheduleDensityException;
import com.yeso.backend.trip.domain.RegionSelectionMode;
import com.yeso.backend.trip.domain.TripContextLockedException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.context.DrawIgnoredConditionResponse;
import com.yeso.backend.trip.presentation.context.DrawRegionRequest;
import com.yeso.backend.trip.presentation.context.DrawRegionResponse;
import com.yeso.backend.trip.presentation.context.PreDrawRegionRequest;
import com.yeso.backend.trip.presentation.context.PreDrawRegionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;

/**
 * 지역 정하기(docs/api/trip.md 3-7). 랜덤 추첨, 조건 추첨, 지도에서 직접 선택을 한 API로 처리한다.
 * 추첨 가능 판정은 {@link RegionEligibilityService}만 쓴다(기준을 복제하지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RegionDrawService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double DISTANCE_NORMALIZER_KM = 300.0;
    /** 7-5 지역 먼저 뽑기는 일수를 모르므로 당일(1일) 기준으로 추첨 가능 여부를 본다. */
    static final int PRE_DRAW_DAYS = 1;
    private static final int MAX_NIGHTS = 6;

    private final TripService tripService;
    private final TripPlanRepository tripPlanRepository;
    private final CourseStorage courseStorage;
    private final RegionEligibilityService regionEligibilityService;
    private final RegionRepository regionRepository;
    private final OnboardingQueryService onboardingQueryService;
    private final RecommendationImpressionLogger impressionLogger;
    private final TasteRanker tasteRanker;
    private final Random random;

    public DrawRegionResponse draw(Long userId, Long tripId, DrawRegionRequest request) {
        RegionSelectionMode mode = parseMode(request.mode());
        List<DrawCondition> requestedConditions = mode == RegionSelectionMode.CONDITIONAL
                ? parseConditions(request.conditions())
                : List.of();
        String requestedDensity = request.scheduleDensity() == null ? null : validateDensity(request.scheduleDensity());
        if (mode == RegionSelectionMode.MANUAL && (request.sigCd() == null || request.sigCd().isBlank())) {
            throw new DrawSigCdRequiredException();
        }

        TripPlan tripPlan = tripService.requireParticipantTrip(userId, tripId);
        tripService.requireNotEnded(tripPlan);
        if (!request.version().equals(tripPlan.getVersion())) {
            throw new TripVersionConflictException();
        }

        boolean hasCourse = courseStorage.hasCourse(tripId);
        if (hasCourse && !Boolean.TRUE.equals(request.replaceCourse())) {
            throw new TripContextLockedException();
        }

        int days = tripPlan.getNights() + 1;
        String density = resolveDensity(requestedDensity, tripPlan, userId);

        DrawOutcome outcome = switch (mode) {
            case RANDOM -> drawRandom(days, density);
            case CONDITIONAL -> drawConditional(tripPlan.getOriginLat(), tripPlan.getOriginLng(), userId, days, density,
                    requestedConditions);
            case MANUAL -> drawManual(request.sigCd(), days, density);
        };

        Region region = regionRepository.findById(outcome.sigCd())
                .orElseThrow(() -> new IllegalStateException("추첨된 지역이 regions 테이블에 없습니다: " + outcome.sigCd()));

        tripPlan.setRegion(region);
        tripPlan.setRegionSelection(mode.name());
        tripPlan.setScheduleDensity(density);
        if (hasCourse) {
            courseStorage.emptyCourse(tripPlan);
        }
        try {
            tripPlanRepository.saveAndFlush(tripPlan);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new TripVersionConflictException();
        }
        recordImpression(userId, tripId, RecommendationImpressionLogger.REGION_DRAW, mode, outcome);

        return new DrawRegionResponse(
                tripId,
                region.getSigCd(),
                region.getProvince(),
                region.getCity(),
                mode.name(),
                density,
                outcome.appliedConditions().stream().map(Enum::name).toList(),
                outcome.ignoredConditions().stream().map(DrawIgnoredConditionResponse::from).toList(),
                outcome.candidateCount(),
                outcome.warnings().stream().map(Enum::name).toList(),
                tripPlan.getVersion());
    }

    /**
     * 7-5 여행 없이 지역 먼저 뽑기(D1). 저장하지 않는다. 일수를 아직 모르므로 1일 기준 추첨 가능 지역에서 뽑고, 그 지역으로
     * 만들 수 있는 최대 박수({@code maxNights})를 함께 준다. 날짜를 고른 뒤 3-3이 그 일수·밀도로 다시 검사한다.
     */
    @Transactional(readOnly = true)
    public PreDrawRegionResponse preDraw(Long userId, PreDrawRegionRequest request) {
        RegionSelectionMode mode = parseMode(request.mode());
        if (mode == RegionSelectionMode.MANUAL) {
            throw new DrawInvalidModeException();
        }
        List<DrawCondition> requestedConditions = mode == RegionSelectionMode.CONDITIONAL
                ? parseConditions(request.conditions())
                : List.of();
        String density = request.scheduleDensity() != null ? validateDensity(request.scheduleDensity())
                : onboardingQueryService.findLatestScheduleDensity(userId).orElse("RELAXED");
        if ((request.originLat() == null) != (request.originLng() == null)) {
            throw InvalidOriginException.missingPair();
        }
        DrawOutcome outcome = mode == RegionSelectionMode.RANDOM
                ? drawRandom(PRE_DRAW_DAYS, density)
                : drawConditional(request.originLat(), request.originLng(), userId, PRE_DRAW_DAYS, density, requestedConditions);
        Region region = regionRepository.findById(outcome.sigCd())
                .orElseThrow(() -> new IllegalStateException("추첨된 지역이 regions 테이블에 없습니다: " + outcome.sigCd()));
        recordImpression(userId, null, RecommendationImpressionLogger.REGION_PRE_DRAW, mode, outcome);
        return new PreDrawRegionResponse(
                region.getSigCd(), region.getProvince(), region.getCity(), region.getLat(), region.getLng(), mode.name(),
                density, maxNights(region.getSigCd(), density),
                outcome.appliedConditions().stream().map(Enum::name).toList(),
                outcome.ignoredConditions().stream().map(DrawIgnoredConditionResponse::from).toList(),
                outcome.candidateCount(),
                outcome.warnings().stream().map(Enum::name).toList());
    }

    /** 이 지역·밀도로 코스를 만들 수 있는 최대 박수(0~6). 1일도 안 되면 -1. */
    public int maxNights(String sigCd, String density) {
        for (int nights = MAX_NIGHTS; nights >= 0; nights--) {
            if (regionEligibilityService.isDrawEligible(sigCd, nights + 1, density)) {
                return nights;
            }
        }
        return -1;
    }

    /** {@code exposure}: 추첨한 후보와 뽑힐 확률(직접 고른 MANUAL이면 빈 목록, 기록하지 않는다). */
    private record DrawOutcome(
            String sigCd, List<DrawCondition> appliedConditions,
            List<DrawIgnoredCondition> ignoredConditions, long candidateCount, List<DrawWarning> warnings,
            List<RecommendationImpressionLogger.Candidate> exposure) {
    }

    private void recordImpression(Long userId, Long tripId, String stage, RegionSelectionMode mode, DrawOutcome outcome) {
        if (outcome.exposure().isEmpty()) {
            return;
        }
        double chosen = outcome.exposure().stream().filter(c -> outcome.sigCd().equals(c.id()))
                .mapToDouble(RecommendationImpressionLogger.Candidate::probability).findFirst().orElse(0);
        String rankerVersion = outcome.appliedConditions().contains(DrawCondition.MY_TASTE)
                ? tasteRanker.version().orElse(null) : null;
        impressionLogger.record(userId, tripId, stage, mode.name(),
                outcome.appliedConditions().stream().map(Enum::name).toList(), rankerVersion, outcome.exposure(),
                List.of(new RecommendationImpressionLogger.Chosen(outcome.sigCd(), 0, chosen)));
    }

    private DrawOutcome drawRandom(int days, String density) {
        List<EligibleRegion> candidates = regionEligibilityService.findEligible(days, density);
        if (candidates.isEmpty()) {
            throw new DrawNoEligibleRegionException();
        }
        EligibleRegion chosen = candidates.get(random.nextInt(candidates.size()));
        double probability = 1.0 / candidates.size();
        List<RecommendationImpressionLogger.Candidate> exposure = candidates.stream()
                .map(c -> new RecommendationImpressionLogger.Candidate(c.sigCd(), null, 1.0, probability)).toList();
        return new DrawOutcome(chosen.sigCd(), List.of(), List.of(), candidates.size(), List.of(), exposure);
    }

    private DrawOutcome drawManual(String sigCd, int days, String density) {
        // isDrawEligible이 없는 지역이면 REGION_NOT_FOUND를 던진다.
        boolean eligible = regionEligibilityService.isDrawEligible(sigCd, days, density);
        if (!eligible) {
            throw new DrawRegionNotEligibleException(sigCd);
        }
        long candidateCount = regionEligibilityService.countEligible(days, density);
        return new DrawOutcome(sigCd, List.of(), List.of(), candidateCount, List.of(), List.of());
    }

    private DrawOutcome drawConditional(
            Double originLat, Double originLng, Long userId, int days, String density, List<DrawCondition> requestedConditions) {
        List<EligibleRegion> candidates = regionEligibilityService.findEligible(days, density);
        if (candidates.isEmpty()) {
            throw new DrawNoEligibleRegionException();
        }

        List<DrawCondition> applied = new ArrayList<>();
        List<DrawIgnoredCondition> ignored = new ArrayList<>();
        Map<String, Double> distanceScores = Map.of();
        Map<String, Double> tasteScores = Map.of();

        if (requestedConditions.contains(DrawCondition.DISTANCE)) {
            if (originLat == null || originLng == null) {
                ignored.add(new DrawIgnoredCondition(DrawCondition.DISTANCE, DrawIgnoredReason.ORIGIN_MISSING));
            } else {
                applied.add(DrawCondition.DISTANCE);
                distanceScores = distanceScores(originLat, originLng, candidates);
            }
        }

        if (requestedConditions.contains(DrawCondition.MY_TASTE)) {
            float[] requesterTaste = onboardingQueryService.findTasteVector(userId).orElse(null);
            TasteRanker.Survey survey = onboardingQueryService.findLatestTasteAnswers(userId)
                    .map(answers -> new TasteRanker.Survey(answers.travelStyles(), answers.travelMotives())).orElse(null);
            Map<String, Double> scores = requesterTaste == null ? Map.of() : tasteScores(candidates, requesterTaste, survey);
            if (scores.isEmpty()) {
                ignored.add(new DrawIgnoredCondition(DrawCondition.MY_TASTE, DrawIgnoredReason.TASTE_NOT_READY));
            } else {
                applied.add(DrawCondition.MY_TASTE);
                tasteScores = scores;
            }
        }

        List<DrawWarning> warnings = applied.isEmpty() ? List.of(DrawWarning.ALL_CONDITIONS_IGNORED) : List.of();
        double[] weights = weights(candidates, distanceScores, tasteScores);
        EligibleRegion chosen = weightedPick(candidates, weights);
        double total = java.util.Arrays.stream(weights).sum();
        List<RecommendationImpressionLogger.Candidate> exposure = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String sigCd = candidates.get(i).sigCd();
            exposure.add(new RecommendationImpressionLogger.Candidate(sigCd, tasteScores.get(sigCd), weights[i], weights[i] / total));
        }
        return new DrawOutcome(chosen.sigCd(), List.copyOf(applied), List.copyOf(ignored), candidates.size(), warnings,
                exposure);
    }

    private static double[] weights(
            List<EligibleRegion> candidates, Map<String, Double> distanceScores, Map<String, Double> tasteScores) {
        double[] weights = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            String sigCd = candidates.get(i).sigCd();
            weights[i] = 1.0 + distanceScores.getOrDefault(sigCd, 0.0) + tasteScores.getOrDefault(sigCd, 0.0);
        }
        return weights;
    }

    private EligibleRegion weightedPick(List<EligibleRegion> candidates, double[] weights) {
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        double pick = random.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights[i];
            if (pick < cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private Map<String, Double> distanceScores(double originLat, double originLng, List<EligibleRegion> candidates) {
        Map<String, Double> scores = new java.util.HashMap<>();
        for (EligibleRegion candidate : candidates) {
            if (candidate.lat() == null || candidate.lng() == null) {
                continue;
            }
            double distanceKm = haversineKm(originLat, originLng, candidate.lat(), candidate.lng());
            double clamped = Math.min(Math.max(distanceKm / DISTANCE_NORMALIZER_KM, 0), 1);
            scores.put(candidate.sigCd(), 1 - clamped);
        }
        return scores;
    }

    /** 요청자의 취향 벡터와 지역의 추천 가능 관광지 벡터가 모두 준비된 지역만 담는다. */
    private Map<String, Double> tasteScores(List<EligibleRegion> candidates, float[] requesterTaste,
                                            TasteRanker.Survey survey) {
        Map<String, Double> scores = new java.util.HashMap<>();
        for (EligibleRegion candidate : candidates) {
            OptionalDouble score = regionEligibilityService.tasteScore(candidate.sigCd(), requesterTaste, survey);
            score.ifPresent(value -> scores.put(candidate.sigCd(), value));
        }
        return scores;
    }

    private String resolveDensity(String requestedDensity, TripPlan tripPlan, Long userId) {
        if (requestedDensity != null) {
            return requestedDensity;
        }
        if (tripPlan.getScheduleDensity() != null) {
            return tripPlan.getScheduleDensity();
        }
        return onboardingQueryService.findLatestScheduleDensity(userId).orElse("RELAXED");
    }

    private static RegionSelectionMode parseMode(String mode) {
        try {
            return RegionSelectionMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DrawInvalidModeException();
        }
    }

    private static List<DrawCondition> parseConditions(List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new DrawNoConditionSelectedException();
        }
        Set<DrawCondition> parsed = EnumSet.noneOf(DrawCondition.class);
        for (String condition : conditions) {
            try {
                parsed.add(DrawCondition.valueOf(condition));
            } catch (IllegalArgumentException ignored) {
                // 알 수 없는 값은 무시한다. 유효한 조건이 하나도 안 남으면 아래에서 걸린다.
            }
        }
        if (parsed.isEmpty()) {
            throw new DrawNoConditionSelectedException();
        }
        return List.copyOf(parsed);
    }

    private static String validateDensity(String density) {
        if (!density.equals("RELAXED") && !density.equals("PACKED")) {
            throw new InvalidScheduleDensityException();
        }
        return density;
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
