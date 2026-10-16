package com.yeso.backend.trip.presentation.context;

import java.time.LocalDate;

/** 3-2. {@code sigCd}를 주면 그 지역이 이 기간에 코스를 만들 수 있는지도 본다(D1). */
public record CheckTripContextRequest(LocalDate startDate, Integer nights, String sigCd, String scheduleDensity) {
}
