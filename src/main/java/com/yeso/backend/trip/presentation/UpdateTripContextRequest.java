package com.yeso.backend.trip.presentation;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record UpdateTripContextRequest(
        LocalDate startDate,
        Integer nights,
        String transport,
        Double originLat,
        Double originLng,
        @NotNull(message = "version을 입력해주세요.") Integer version
) {
}
