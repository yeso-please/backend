package com.yeso.backend.trip.presentation.course;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 5-6 식당 검색 응답. */
public record RestaurantSearchResponse(
        String itemId,
        RestaurantOriginResponse origin,
        int page,
        @JsonProperty("isEnd") boolean isEnd,
        List<RestaurantCandidateResponse> items) {
}
