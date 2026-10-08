package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RestaurantQueryService.Candidate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 코스 생성 때 식사 슬롯에 넣을 식당을 고른다(docs/api/trip.md 5-1). 기준점에서 가까운 순으로 정렬된 후보에서
 * 이미 배정한 식당을 뺀 상위 {@value #TOP_N}개 중 무작위 하나다. DB를 읽지 않는 순수 클래스다.
 */
class MealRestaurantPicker {

    static final int TOP_N = 10;

    /**
     * @param nearestFirst 기준점에서 가까운 순으로 정렬된 후보
     * @param usedExternalIds 이 코스에서 이미 배정한 식당의 {@code externalId}
     */
    Optional<Candidate> pick(List<Candidate> nearestFirst, Set<String> usedExternalIds, RandomGenerator random) {
        List<Candidate> top = nearestFirst.stream()
                .filter(candidate -> !usedExternalIds.contains(candidate.externalId()))
                .limit(TOP_N)
                .toList();
        if (top.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(top.get(random.nextInt(top.size())));
    }
}
