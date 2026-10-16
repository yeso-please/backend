package com.yeso.backend.trip.presentation.course;

import java.util.List;

/**
 * 하루 코스의 지도 경로(docs/api/trip.md 5-7). 프론트는 {@code legs[].path}를 그대로 이어 지도에 선을 그린다.
 *
 * @param transport 이 경로의 이동수단(요청 {@code transport}, 없으면 여행 이동수단)
 */
public record CourseRoutesResponse(Long tripId, int version, int dayIndex, String transport, List<Leg> legs) {

    /**
     * 바로 앞 지점에서 이 지점까지의 구간. 지점은 관광지와 식당을 고른 식사이고, 식당 미정 식사는 건너뛴다(5장과 같은 규칙).
     *
     * @param source          {@code ROAD}(카카오모빌리티 자동차 길찾기) | {@code WALK}(카카오맵 도보 경로) |
     *                        {@code TRANSIT}(카카오맵 대중교통 경로) | {@code STRAIGHT}(직선, 경로를 못 받음)
     * @param distanceMeters  경로 거리 또는 직선거리(STRAIGHT)
     * @param durationMinutes 경로 소요시간 또는 5장과 같은 추정 시간(STRAIGHT)
     * @param path            [위도, 경도] 순서의 점 목록. STRAIGHT는 출발·도착 두 점이다
     * @param transfers       대중교통 환승 횟수(TRANSIT만)
     * @param fare            대중교통 요금(원, TRANSIT만, 원천이 주지 않으면 null)
     */
    public record Leg(String fromItemId, String toItemId, String source, int distanceMeters, int durationMinutes,
                      List<double[]> path, Integer transfers, Integer fare) {
    }
}
