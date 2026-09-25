package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.trip.domain.TripDayWindow;

import java.time.LocalDate;

public record DayWindowResponse(int dayIndex, LocalDate date, String windowStart, String windowEnd) {
    public static DayWindowResponse from(TripDayWindow window) {
        return new DayWindowResponse(
                window.dayIndex(), window.date(), window.windowStart().toString(), window.windowEnd().toString());
    }
}
