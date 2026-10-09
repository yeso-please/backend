package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.CourseInvalidOperationException;
import com.yeso.backend.trip.domain.CourseInvalidQueryException;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseItemNotFoundException;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.TravelTimeEstimator;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.presentation.course.AlternativeCoursesResponse;
import com.yeso.backend.trip.presentation.course.AlternativeCoursesResponse.Group;
import com.yeso.backend.trip.presentation.course.AlternativeCoursesResponse.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 5-4 같은 지역의 추천 가능한 대체·추가 후보. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseAlternativesService {
    private final TripService tripService;
    private final CourseItemRepository courseItemRepository;
    private final RegionEligibilityService eligibilityService;
    private final CourseMaterialService materialService;
    private final OnboardingQueryService onboardingQueryService;

    public AlternativeCoursesResponse alternatives(Long userId, Long tripId, String itemId,
                                                   String categoryValue, Integer limitValue, String query) {
        AttractionCategory category = parseCategory(categoryValue);
        int limit = limitValue == null ? 10 : limitValue;
        if (limit < 1 || limit > 30) {
            throw new CourseInvalidQueryException();
        }
        String q = query == null ? null : query.trim();
        if (q != null && (q.isEmpty() || q.length() > 50)) {
            throw new CourseInvalidQueryException();
        }
        TripPlan trip = tripService.requireParticipantTrip(userId, tripId);
        List<CourseItem> course = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        if (course.isEmpty()) {
            throw new CourseNotFoundException(tripId);
        }
        CourseItem target = itemId == null ? null : course.stream()
                .filter(item -> item.itemId().equals(itemId)).findFirst()
                .orElseThrow(() -> new CourseItemNotFoundException(itemId));
        if (target != null && target.isMeal()) {
            throw new CourseInvalidOperationException("교체 대상은 관광지여야 합니다: " + itemId);
        }
        tripService.requireNotEnded(trip);

        Set<Long> used = course.stream().filter(item -> !item.isMeal()).map(CourseItem::getAttractionId)
                .collect(Collectors.toSet());
        String search = q;
        List<CourseCandidate> candidates = eligibilityService.findCourseCandidates(trip.getRegion().getSigCd()).stream()
                .filter(candidate -> !used.contains(candidate.attractionId()))
                .filter(candidate -> category == null || candidate.category() == category)
                .filter(candidate -> search == null || candidate.name().contains(search))
                .toList();
        float[] taste = onboardingQueryService.findTasteVector(userId).orElse(null);
        Map<Long, float[]> vectors = taste == null ? Map.of() : materialService.findAttractionVectors(
                candidates.stream().map(CourseCandidate::attractionId).toList());
        CourseCandidate previous = adjacent(course, target, -1);
        CourseCandidate next = adjacent(course, target, 1);
        TasteEvidence evidence = onboardingQueryService.findLatestTasteAnswers(userId)
                .map(answers -> TasteEvidence.from(answers.travelStyles(), answers.travelMotives()))
                .orElse(TasteEvidence.NONE);
        AttractionCategory targetCategory = target == null ? null
                : materialService.findAttractionViews(List.of(target.getAttractionId())).values().stream()
                        .map(view -> view.category()).findFirst().orElse(null);
        List<Group> groups = new ArrayList<>();
        for (AttractionCategory type : AttractionCategory.values()) {
            if (category != null && type != category) {
                continue;
            }
            List<CourseCandidate> ranked = candidates.stream().filter(candidate -> candidate.category() == type)
                    .sorted(Comparator.comparingDouble((CourseCandidate candidate) -> score(candidate, previous, next,
                            taste, vectors.get(candidate.attractionId()))).reversed()
                            .thenComparing(CourseCandidate::attractionId))
                    .limit(limit)
                    .toList();
            Set<Long> similarTop = topBySimilarity(ranked, taste, vectors);
            List<Item> items = ranked.stream()
                    .map(candidate -> {
                        Integer travel = previous == null ? null : TravelTimeEstimator.minutes(previous.lat(),
                                previous.lng(), candidate.lat(), candidate.lng(), trip.getTransport());
                        return new Item(candidate.attractionId(), candidate.name(), type.name(),
                                candidate.thumbnailUrl(), candidate.lat(), candidate.lng(), candidate.stayMinutes(),
                                travel, reason(candidate, targetCategory, evidence, travel,
                                        similarTop.contains(candidate.attractionId())));
                    })
                    .toList();
            groups.add(new Group(type.name(), label(type), items));
        }
        return new AlternativeCoursesResponse(itemId, groups);
    }

    /** 후보 중 취향 유사도 상위 {@link CourseGenerator#SIMILAR_TOP}곳. 취향 벡터가 없으면 빈 집합. */
    private static Set<Long> topBySimilarity(List<CourseCandidate> ranked, float[] taste, Map<Long, float[]> vectors) {
        if (taste == null) {
            return Set.of();
        }
        return ranked.stream()
                .filter(candidate -> vectors.containsKey(candidate.attractionId()))
                .sorted(Comparator.comparingDouble((CourseCandidate candidate) ->
                        cosine(taste, vectors.get(candidate.attractionId()))).reversed()
                        .thenComparing(CourseCandidate::attractionId))
                .limit(CourseGenerator.SIMILAR_TOP)
                .map(CourseCandidate::attractionId)
                .collect(Collectors.toSet());
    }

    /**
     * 교체·추가 후보의 추천 이유(docs/api/trip.md 추천 이유 5-4). 같은 유형 → 설문 근거 → 동선 → 취향 유사도 순으로
     * 최대 2개를 " · "로 잇는다. 근거가 없으면 null.
     */
    static String reason(CourseCandidate candidate, AttractionCategory targetCategory, TasteEvidence evidence,
                         Integer travelMinutes, boolean similarTop) {
        List<String> parts = new ArrayList<>();
        if (targetCategory != null && targetCategory == candidate.category() && targetCategory != AttractionCategory.ETC) {
            parts.add("바꾸려는 곳과 같은 " + label(targetCategory) + " 장소예요");
        }
        evidence.reasonsFor(candidate.category()).stream().findFirst().ifPresent(parts::add);
        if (travelMinutes != null) {
            parts.add("앞 장소에서 약 " + travelMinutes + "분이에요");
        }
        if (similarTop) {
            parts.add(CourseGenerator.SIMILAR_REASON);
        }
        return parts.isEmpty() ? null : String.join(" · ", parts.subList(0, Math.min(2, parts.size())));
    }

    private static CourseCandidate adjacent(List<CourseItem> course, CourseItem target, int direction) {
        if (target == null) {
            return null;
        }
        int index = course.indexOf(target);
        for (int at = index + direction; at >= 0 && at < course.size(); at += direction) {
            CourseItem item = course.get(at);
            if (item.getDayIndex() != target.getDayIndex()) {
                break;
            }
            if (!item.isMeal()) {
                var attraction = item.getAttraction();
                if (attraction.getLat() != null && attraction.getLng() != null) {
                    return new CourseCandidate(item.getAttractionId(), attraction.getName(), null, 0,
                            null, attraction.getLat(), attraction.getLng(), null);
                }
                break;
            }
        }
        return null;
    }

    private static double score(CourseCandidate candidate, CourseCandidate previous, CourseCandidate next,
                                float[] taste, float[] vector) {
        double distance = 0;
        if (previous != null) {
            distance += TravelTimeEstimator.distanceKm(previous.lat(), previous.lng(), candidate.lat(), candidate.lng());
        }
        if (next != null) {
            distance += TravelTimeEstimator.distanceKm(candidate.lat(), candidate.lng(), next.lat(), next.lng());
        }
        return 10 * cosine(taste, vector) - distance;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / Math.sqrt(normA * normB);
    }

    private static AttractionCategory parseCategory(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AttractionCategory.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new CourseInvalidQueryException();
        }
    }

    private static String label(AttractionCategory category) {
        return switch (category) {
            case NATURE -> "자연";
            case HISTORY_CULTURE -> "역사·문화";
            case ACTIVITY -> "체험·레포츠";
            case WALK_REST -> "산책·휴식";
            case ETC -> "기타";
        };
    }
}
