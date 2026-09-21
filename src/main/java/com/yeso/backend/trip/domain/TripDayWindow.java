package com.yeso.backend.trip.domain;

import java.time.LocalDate;
import java.time.LocalTime;

public record TripDayWindow(int dayIndex, LocalDate date, LocalTime windowStart, LocalTime windowEnd) {
}
