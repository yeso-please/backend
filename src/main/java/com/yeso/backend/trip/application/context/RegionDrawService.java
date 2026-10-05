package com.yeso.backend.trip.application.context;

import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.EligibleRegion;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
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
import com.yeso.backend.trip.domain.InvalidScheduleDensityException;
import com.yeso.backend.trip.domain.RegionSelectionMode;
import com.yeso.backend.trip.domain.TripContextLockedException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.context.DrawIgnoredConditionResponse;
import com.yeso.backend.trip.presentation.context.DrawRegionRequest;
import com.yeso.backend.trip.presentation.context.DrawRegionResponse;
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

    private final TripService tripService;
    private final TripPlanRepository tripPlanRepository;
    private final CourseStorage courseStorage;
    private final RegionEligibilityService regionEligibilityService;
    private final RegionRepository regionRepository;
    private final OnboardingQueryService onboardingQueryService;
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
            case CONDITIONAL -> drawConditional(tripPlan, userId, days, density, requestedConditions);
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

    private record DrawOutcome(
            String sigCd, List<DrawCondition> appliedConditions,
            List<DrawIgnoredCondition> ignoredConditions, long candidateCount, List<DrawWarning> warnings) {
    }

    private DrawOutcome drawRandom(int days, String density) {
        List<EligibleRegion> candidates = regionEligibilityService.findEligible(days, density);
        if (candidates.isEmpty()) {
            throw new DrawNoEligibleRegionException();
        }
        EligibleRegion chosen = candidates.get(random.nextInt(candidates.size()));
        return new DrawOutcome(chosen.sigCd(), List.of(), List.of(), candidates.size(), List.of());
    }

    private DrawOutcome drawManual(String sigCd, int days, String density) {
        // isDrawEligible이 없는 지역이면 REGION_NOT_FOUND를 던진다.
        boolean eligible = regionEligibilityService.isDrawEligible(sigCd, days, density);
        if (!eligible) {
            throw new DrawRegionNotEligibleException(sigCd);
        }
        long candidateCount = regionEligibilityService.countEligible(days, density);
        return new DrawOutcome(sigCd, List.of(), List.of(), candidateCount, List.of());
    }

    private DrawOutcome drawConditional(
            TripPlan tripPlan, Long userId, int days, String density, List<DrawCondition> requestedConditions) {
        List<EligibleRegion> candidates = regionEligibilityService.findEligible(days, density);
        if (candidates.isEmpty()) {
            throw new DrawNoEligibleRegionException();
        }

        List<DrawCondition> applied = new ArrayList<>();
        List<DrawIgnoredCondition> ignored = new ArrayList<>();
        Map<String, Double> distanceScores = Map.of();
        Map<String, Double> tasteScores = Map.of();

        if (requestedConditions.contains(DrawCondition.DISTANCE)) {
            if (tripPlan.getOriginLat() == null) {
                ignored.add(new DrawIgnoredCondition(DrawCondition.DISTANCE, DrawIgnoredReason.ORIGIN_MISSING));
            } else {
                applied.add(DrawCondition.DISTANCE);
                distanceScores = distanceScores(tripPlan, candidates);
            }
        }

        if (requestedConditions.contains(DrawCondition.MY_TASTE)) {
            float[] requesterTaste = onboardingQueryService.findTasteVector(userId).orElse(null);
            Map<String, Double> scores = requesterTaste == null ? Map.of() : tasteScores(candidates, requesterTaste);
            if (scores.isEmpty()) {
                ignored.add(new DrawIgnoredCondition(DrawCondition.MY_TASTE, DrawIgnoredReason.TASTE_NOT_READY));
            } else {
                applied.add(DrawCondition.MY_TASTE);
                tasteScores = scores;
            }
        }

        List<DrawWarning> warnings = applied.isEmpty() ? List.of(DrawWarning.ALL_CONDITIONS_IGNORED) : List.of();
        EligibleRegion chosen = weightedPick(candidates, distanceScores, tasteScores);
        return new DrawOutcome(chosen.sigCd(), List.copyOf(applied), List.copyOf(ignored), candidates.size(), warnings);
    }

    private EligibleRegion weightedPick(
            List<EligibleRegion> candidates, Map<String, Double> distanceScores, Map<String, Double> tasteScores) {
        double[] weights = new double[candidates.size()];
        double total = 0;
        for (int i = 0; i < candidates.size(); i++) {
            String sigCd = candidates.get(i).sigCd();
            double weight = 1.0 + distanceScores.getOrDefault(sigCd, 0.0) + tasteScores.getOrDefault(sigCd, 0.0);
            weights[i] = weight;
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

    private Map<String, Double> distanceScores(TripPlan tripPlan, List<EligibleRegion> candidates) {
        Map<String, Double> scores = new java.util.HashMap<>();
        for (EligibleRegion candidate : candidates) {
            if (candidate.lat() == null || candidate.lng() == null) {
                continue;
            }
            double distanceKm = haversineKm(
                    tripPlan.getOriginLat(), tripPlan.getOriginLng(), candidate.lat(), candidate.lng());
            double clamped = Math.min(Math.max(distanceKm / DISTANCE_NORMALIZER_KM, 0), 1);
            scores.put(candidate.sigCd(), 1 - clamped);
        }
        return scores;
    }

    /** 요청자의 취향 벡터와 지역의 추천 가능 관광지 벡터가 모두 준비된 지역만 담는다. */
    private Map<String, Double> tasteScores(List<EligibleRegion> candidates, float[] requesterTaste) {
        Map<String, Double> scores = new java.util.HashMap<>();
        for (EligibleRegion candidate : candidates) {
            OptionalDouble score = regionEligibilityService.tasteScore(candidate.sigCd(), requesterTaste);
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
