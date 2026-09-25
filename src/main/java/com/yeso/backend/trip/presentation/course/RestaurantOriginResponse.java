package com.yeso.backend.trip.presentation.course;

/**
 * 식당 추천·검색의 기준점(docs/api/trip.md 5-5·5-6 {@code origin}).
 *
 * @param type {@code PREVIOUS_ATTRACTION} | {@code REGION_CENTER}
 * @param attractionId {@code PREVIOUS_ATTRACTION}일 때만 있다
 */
public record RestaurantOriginResponse(String type, Long attractionId, double lat, double lng) {

    public static RestaurantOriginResponse previousAttraction(Long attractionId, double lat, double lng) {
        return new RestaurantOriginResponse("PREVIOUS_ATTRACTION", attractionId, lat, lng);
    }

    public static RestaurantOriginResponse regionCenter(double lat, double lng) {
        return new RestaurantOriginResponse("REGION_CENTER", null, lat, lng);
    }
}
