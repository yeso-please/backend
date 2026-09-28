package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.trip.domain.TripPlan;

import java.time.LocalDate;

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
        int version
) {
    public static TripContextResponse of(TripPlan tripPlan, boolean hasCourse) {
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
                tripPlan.getVersion());
    }
}
