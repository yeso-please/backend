package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.attraction.domain.IneligibleReason;
import com.yeso.backend.attraction.domain.RegionInvalidDaysException;
import com.yeso.backend.attraction.domain.RegionNotFoundException;
import com.yeso.backend.attraction.infrastructure.RegionQualityRepository;
import com.yeso.backend.attraction.infrastructure.RegionQualityRepository.RegionQualityRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 추첨 가능 지역·추천 가능 관광지 판정(docs/api/attraction.md 7장 머리). 다른 모듈이 쓰는 공개 계약이다.
 * 지역 정하기(trip 3-2·3-7)와 코스 생성(trip 5-1)이 판정 기준을 복제하지 않고 여기 결과만 쓴다.
 *
 * <p>추첨 가능 지역: 최근 승인 소개문 + 그 소개문의 검증된 대표 이미지 +
 * 추천 가능 관광지 {@code days × 밀도 상한 + min(days, 3)}개 이상(RELAXED 4, PACKED 6).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionEligibilityService {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 7;

    private final RegionQualityRepository regionQualityRepository;
    private final CourseMaterialService courseMaterialService;

    /** 지도에 그릴 지역 하나와 추첨 가능 여부. */
    public record RegionEligibility(
            String sigCd, String province, String city, Double centerLat, Double centerLng,
            boolean drawEligible, List<IneligibleReason> ineligibleReasons) {
    }

    /** 추첨할 수 있는 지역. 거리 조건 추첨에 중심 좌표를 쓴다. */
    public record EligibleRegion(String sigCd, String province, String city, Double lat, Double lng) {
    }

    /** 코스 생성 후보 관광지. 체류 시간은 유형별 기본값(참고용)이다. */
    public record CourseCandidate(
            Long attractionId, String name, AttractionCategory category, int stayMinutes,
            String address, double lat, double lng, String thumbnailUrl) {
    }

    /** 전국 지역과 각 지역의 추첨 가능 여부(7-1). */
    public List<RegionEligibility> evaluateAll(int days, String scheduleDensity) {
        int required = requiredAttractions(days, scheduleDensity);
        return regionQualityRepository.findAllQuality().stream()
                .map(row -> evaluate(row, required))
                .toList();
    }

    /** 이 일수·밀도로 뽑을 수 있는 지역 수(3-2 eligibleRegionCount). */
    public long countEligible(int days, String scheduleDensity) {
        return evaluateAll(days, scheduleDensity).stream().filter(RegionEligibility::drawEligible).count();
    }

    /** 이 일수·밀도로 뽑을 수 있는 지역 목록(3-7 랜덤·조건 추첨). */
    public List<EligibleRegion> findEligible(int days, String scheduleDensity) {
        return evaluateAll(days, scheduleDensity).stream()
                .filter(RegionEligibility::drawEligible)
                .map(region -> new EligibleRegion(
                        region.sigCd(), region.province(), region.city(), region.centerLat(), region.centerLng()))
                .toList();
    }

    /** 이 지역을 뽑아도 되는지(3-7 직접 선택). 없는 지역이면 {@code REGION_NOT_FOUND}. */
    public boolean isDrawEligible(String sigCd, int days, String scheduleDensity) {
        int required = requiredAttractions(days, scheduleDensity);
        RegionQualityRow row = regionQualityRepository.findQuality(sigCd)
                .orElseThrow(() -> new RegionNotFoundException(sigCd));
        return evaluate(row, required).drawEligible();
    }

    /**
     * 이 지역이 요청자 취향에 얼마나 맞는지(3-7 MY_TASTE). 관광지 임베딩(#54)을 아직 저장하지 않아
     * 호환 가능한 관광지 벡터 중 유사도 상위 5개 평균을 0~1로 정규화한다.
     */
    public OptionalDouble tasteScore(String sigCd, float[] requesterVector) {
        if (requesterVector == null || requesterVector.length == 0) {
            return OptionalDouble.empty();
        }
        List<Long> attractionIds = findCourseCandidates(sigCd).stream()
                .map(CourseCandidate::attractionId).toList();
        List<float[]> vectors = courseMaterialService.findAttractionVectors(attractionIds).values().stream()
                .filter(vector -> vector.length == requesterVector.length)
                .toList();
        if (vectors.isEmpty()) {
            return OptionalDouble.empty();
        }
        double averageTopSimilarity = vectors.stream()
                .mapToDouble(vector -> cosine(requesterVector, vector))
                .boxed()
                .sorted(java.util.Comparator.reverseOrder())
                .limit(5)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElseThrow();
        return OptionalDouble.of((averageTopSimilarity + 1) / 2);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    /** 코스 생성 후보(5-1). 추천 가능 관광지만, 관광지 ID 순. 없는 지역이면 {@code REGION_NOT_FOUND}. */
    public List<CourseCandidate> findCourseCandidates(String sigCd) {
        if (regionQualityRepository.findQuality(sigCd).isEmpty()) {
            throw new RegionNotFoundException(sigCd);
        }
        return regionQualityRepository.findRecommendableAttractions(sigCd).stream()
                .map(row -> new CourseCandidate(
                        row.getId(), row.getName(), AttractionCategory.fromContentType(row.getContentTypeId()),
                        AttractionCategory.stayMinutesOf(row.getContentTypeId()), row.getAddr(),
                        row.getLat(), row.getLng(), row.getThumbnailUrl()))
                .toList();
    }

    /** {@code days × 밀도 상한 + min(days, 3)}. */
    static int requiredAttractions(int days, String scheduleDensity) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new RegionInvalidDaysException(days);
        }
        return days * dailyTarget(scheduleDensity) + Math.min(days, 3);
    }

    /** 하루 관광지 목표 수. 추첨 판정과 코스 생성(trip 5-1)이 같이 쓴다. */
    public static int dailyTarget(String scheduleDensity) {
        return switch (scheduleDensity) {
            case "RELAXED" -> 4;
            case "PACKED" -> 6;
            case null, default -> throw new IllegalArgumentException("밀도는 RELAXED 또는 PACKED여야 합니다: " + scheduleDensity);
        };
    }

    private static RegionEligibility evaluate(RegionQualityRow row, int required) {
        List<IneligibleReason> reasons = new ArrayList<>();
        if (!row.getHasApprovedContent()) {
            reasons.add(IneligibleReason.NO_APPROVED_CONTENT);
        }
        if (!row.getHasValidHeroImage()) {
            reasons.add(IneligibleReason.NO_VALID_HERO_IMAGE);
        }
        if (row.getRecommendableCount() < required) {
            reasons.add(IneligibleReason.INSUFFICIENT_ATTRACTIONS);
        }
        return new RegionEligibility(
                row.getSigCd(), row.getProvince(), row.getCity(), row.getLat(), row.getLng(),
                reasons.isEmpty(), List.copyOf(reasons));
    }
}
