package com.yeso.backend.trip.presentation.context;

import java.time.LocalDate;

public record CheckTripContextRequest(LocalDate startDate, Integer nights) {
}
