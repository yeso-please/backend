package com.yeso.backend.trip.presentation.context;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 이동수단·출발지만 바꾼다. {@code startDate}·{@code nights}는 받지 않는 필드지만, 보냈을 때
 * 조용히 무시하지 않고 400으로 알려주기 위해 선언해 둔다(생성 후 날짜 변경 불가).
 */
public record UpdateTripContextRequest(
        String transport,
        Double originLat,
        Double originLng,
        LocalDate startDate,
        Integer nights,
        @NotNull(message = "version을 입력해주세요.") Integer version
) {
}
