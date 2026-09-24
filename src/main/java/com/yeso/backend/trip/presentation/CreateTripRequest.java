package com.yeso.backend.trip.presentation;

import java.time.LocalDate;

/**
 * nights/transport는 Bean Validation 대신 서비스 계층에서 검증한다 — 형식 오류가 아니라
 * TRIP_INVALID_NIGHTS/TRIP_INVALID_TRANSPORT 같은 도메인 코드로 응답해야 하기 때문이다.
 */
public record CreateTripRequest(
        LocalDate startDate,
        Integer nights,
        String transport,
        Double originLat,
        Double originLng
) {
}
