package com.yeso.backend.trip.domain;

import java.time.LocalDate;

/** overlap 오류의 {@code details.conflicts} 항목 — 요청자가 참여 중인 trip의 ID/title/기간만 노출한다. */
public record TripConflict(Long tripId, String title, LocalDate startDate, LocalDate endDate) {

    public static TripConflict from(TripPlan tripPlan) {
        return new TripConflict(tripPlan.getId(), tripPlan.displayTitle(), tripPlan.getStartDate(), tripPlan.getEndDate());
    }
}
