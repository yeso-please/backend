package com.yeso.backend.trip.presentation.context;

import java.util.List;

/**
 * 7-5 지역 먼저 뽑기. {@code mode}는 RANDOM | CONDITIONAL(지도에서 직접 고르기는 뽑기가 아니라 3-3에 바로 보낸다).
 * 출발지는 거리 조건에만 쓰고 저장하지 않는다.
 */
public record PreDrawRegionRequest(
        String mode, List<String> conditions, Double originLat, Double originLng, String scheduleDensity) {
}
