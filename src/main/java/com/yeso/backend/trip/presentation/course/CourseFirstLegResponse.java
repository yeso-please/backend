package com.yeso.backend.trip.presentation.course;

/**
 * 지금 위치에서 그날 첫 관광지까지(docs/api/trip.md 5-8). 위치는 저장하지 않는다.
 *
 * @param straightMeters 지금 위치와 첫 관광지의 직선거리
 * @param walk           도보. 직선거리가 {@code 10km}를 넘으면 계산하지 않고 null
 */
public record CourseFirstLegResponse(Long tripId, int dayIndex, String itemId, Long attractionId, String name,
                                     double lat, double lng, int straightMeters, Mode car, Mode walk, Mode transit) {

    /**
     * @param source          {@code ROAD} | {@code WALK} | {@code TRANSIT}(실제 경로) | {@code STRAIGHT}(경로를 못 받아 직선 추정)
     * @param transfers       대중교통 환승 횟수(TRANSIT만)
     * @param fare            대중교통 요금(원, TRANSIT만, 원천이 주지 않으면 null)
     */
    public record Mode(String source, int distanceMeters, int durationMinutes, Integer transfers, Integer fare) {
    }
}
