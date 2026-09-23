package com.yeso.backend.trip.presentation;

import java.time.LocalDate;
import java.util.List;

public record TripContextCheckResponse(
        boolean available,
        LocalDate endDate,
        List<TripConflictResponse> conflicts,
        List<DayWindowResponse> dayWindows
) {
}
