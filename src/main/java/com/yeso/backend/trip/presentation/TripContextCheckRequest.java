package com.yeso.backend.trip.presentation;

import java.time.LocalDate;

public record TripContextCheckRequest(LocalDate startDate, Integer nights) {
}
