package com.yeso.backend.trip.presentation.course;

import java.util.List;

/**
 * 하루 코스의 지도 경로(docs/api/trip.md 5-7). 프론트는 {@code legs[].path}를 그대로 이어 지도에 선을 그린다.
 *
 * @param transport 여행 이동수단. {@code CAR}가 아니면 모든 구간이 {@code STRAIGHT}다
 */
public record CourseRoutesResponse(Long tripId, int version, int dayIndex, String transport, List<Leg> legs) {

    /**
     * 바로 앞 지점에서 이 지점까지의 구간. 지점은 관광지와 식당을 고른 식사이고, 식당 미정 식사는 건너뛴다(5장과 같은 규칙).
     *
     * @param source          {@code ROAD}(카카오 자동차 길찾기) | {@code STRAIGHT}(직선, 길찾기를 못 했거나 자동차가 아님)
     * @param distanceMeters  도로 거리(ROAD) 또는 직선거리(STRAIGHT)
     * @param durationMinutes 도로 소요시간(ROAD) 또는 5장과 같은 추정 시간(STRAIGHT)
     * @param path            [위도, 경도] 순서의 점 목록. STRAIGHT는 출발·도착 두 점이다
     */
    public record Leg(String fromItemId, String toItemId, String source, int distanceMeters, int durationMinutes,
                      List<double[]> path) {
    }
}
