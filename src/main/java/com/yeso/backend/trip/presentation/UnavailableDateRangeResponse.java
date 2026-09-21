package com.yeso.backend.trip.presentation;

import java.time.LocalDate;

public record UnavailableDateRangeResponse(Long tripId, LocalDate startDate, LocalDate endDate) {
}
