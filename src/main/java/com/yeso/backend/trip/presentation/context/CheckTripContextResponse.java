package com.yeso.backend.trip.presentation.context;

import java.time.LocalDate;
import java.util.List;

public record CheckTripContextResponse(
        boolean available,
        LocalDate endDate,
        List<TripConflictResponse> conflicts,
        List<DayWindowResponse> dayWindows
) {
}
