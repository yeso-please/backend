package com.yeso.backend.trip.presentation.course;

import java.util.List;

/** 5-5. 공공 지정 식당과 지역 음식 테마는 추가 기능이라 MVP에서는 빈 배열이다. */
public record RestaurantRecommendationsResponse(
        String itemId, RestaurantOriginResponse origin, List<Object> regionFoodThemes, List<Section> sections) {
    public record Section(String source, String label, List<RestaurantCandidateResponse> items) {
    }
}
