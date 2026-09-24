package com.yeso.backend.trip.presentation;

import com.yeso.backend.trip.domain.TripDayWindowCalculator;
import com.yeso.backend.trip.domain.TripPlan;

import java.time.LocalDate;
import java.util.List;

public record TripContextResponse(
        Long id,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        int nights,
        String transport,
        Double originLat,
        Double originLng,
        int version,
        List<DayWindowResponse> dayWindows,
        boolean draftInvalidated
) {
    public static TripContextResponse of(TripPlan tripPlan, boolean draftInvalidated) {
        List<DayWindowResponse> dayWindows = TripDayWindowCalculator
                .calculate(tripPlan.getStartDate(), tripPlan.getNights())
                .stream()
                .map(DayWindowResponse::from)
                .toList();
        return new TripContextResponse(
                tripPlan.getId(),
                tripPlan.getStatus().name(),
                tripPlan.getStartDate(),
                tripPlan.getEndDate(),
                tripPlan.getNights(),
                tripPlan.getTransport().name(),
                tripPlan.getOriginLat(),
                tripPlan.getOriginLng(),
                tripPlan.getVersion(),
                dayWindows,
                draftInvalidated);
    }
}
