package com.yeso.backend.trip.presentation.course;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 5-6 식당 검색 query. {@code query}를 생략하면 주변 음식점 전체다.
 *
 * @param radius 미터. 기본 5000, 최대 20000
 * @param page 1부터. 기본 1, 최대 45
 */
public record RestaurantSearchRequest(
        @NotBlank String itemId,
        @Size(min = 1, max = 50) String query,
        @Min(1) @Max(20000) Integer radius,
        @Min(1) @Max(45) Integer page) {

    public static final int DEFAULT_RADIUS = 5000;
    public static final int DEFAULT_PAGE = 1;

    public int radiusOrDefault() {
        return radius == null ? DEFAULT_RADIUS : radius;
    }

    public int pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }
}
