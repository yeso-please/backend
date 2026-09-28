package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.trip.domain.TripConflict;

import java.time.LocalDate;

public record TripConflictResponse(Long tripId, String title, LocalDate startDate, LocalDate endDate) {
    public static TripConflictResponse from(TripConflict conflict) {
        return new TripConflictResponse(
                conflict.tripId(), conflict.title(), conflict.startDate(), conflict.endDate());
    }
}
