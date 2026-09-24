package com.yeso.backend.trip.presentation;

import com.yeso.backend.trip.domain.TripDayWindowCalculator;
import com.yeso.backend.trip.domain.TripPlan;

import java.time.LocalDate;
import java.util.List;

public record TripContextResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        int nights,
        String transport,
        Double originLat,
        Double originLng,
        String regionSigCd,
        String regionSelection,
        String scheduleDensity,
        boolean hasCourse,
        int version,
        List<DayWindowResponse> dayWindows
) {
    public static TripContextResponse of(TripPlan tripPlan, boolean hasCourse) {
        List<DayWindowResponse> dayWindows = TripDayWindowCalculator
                .calculate(tripPlan.getStartDate(), tripPlan.getNights())
                .stream()
                .map(DayWindowResponse::from)
                .toList();
        return new TripContextResponse(
                tripPlan.getId(),
                tripPlan.getStartDate(),
                tripPlan.getEndDate(),
                tripPlan.getNights(),
                tripPlan.getTransport().name(),
                tripPlan.getOriginLat(),
                tripPlan.getOriginLng(),
                tripPlan.getRegion() == null ? null : tripPlan.getRegion().getSigCd(),
                tripPlan.getRegionSelection(),
                tripPlan.getScheduleDensity(),
                hasCourse,
                tripPlan.getVersion(),
                dayWindows);
    }
}
