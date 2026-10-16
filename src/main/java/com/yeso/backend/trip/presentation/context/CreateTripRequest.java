package com.yeso.backend.trip.presentation.context;

import java.time.LocalDate;

/**
 * 3-3 여행 만들기. nights/transport는 Bean Validation 대신 서비스 계층에서 검증한다 — 형식 오류가 아니라
 * TRIP_INVALID_NIGHTS/TRIP_INVALID_TRANSPORT 같은 도메인 코드로 응답해야 하기 때문이다.
 * {@code transport}는 선택값(생략하면 CAR). 지역을 먼저 골랐으면 {@code regionSigCd}로 함께 저장한다(D1).
 */
public record CreateTripRequest(
        LocalDate startDate,
        Integer nights,
        String transport,
        Double originLat,
        Double originLng,
        String regionSigCd,
        String regionSelection,
        String scheduleDensity
) {
}
