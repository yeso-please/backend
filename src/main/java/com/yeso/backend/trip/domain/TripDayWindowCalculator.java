package com.yeso.backend.trip.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 당일은 12:00~20:00, 다일은 첫날 12:00~20:00·중간 09:00~20:00·마지막 날 09:00~17:00이다
 * (docs/api/trip.md 3장 dayWindows).
 */
public final class TripDayWindowCalculator {

    private static final LocalTime SINGLE_DAY_START = LocalTime.of(12, 0);
    private static final LocalTime SINGLE_DAY_END = LocalTime.of(20, 0);
    private static final LocalTime FIRST_DAY_START = LocalTime.of(12, 0);
    private static final LocalTime FIRST_DAY_END = LocalTime.of(20, 0);
    private static final LocalTime MIDDLE_DAY_START = LocalTime.of(9, 0);
    private static final LocalTime MIDDLE_DAY_END = LocalTime.of(20, 0);
    private static final LocalTime LAST_DAY_START = LocalTime.of(9, 0);
    private static final LocalTime LAST_DAY_END = LocalTime.of(17, 0);

    private TripDayWindowCalculator() {
    }

    public static List<TripDayWindow> calculate(LocalDate startDate, int nights) {
        int totalDays = nights + 1;
        List<TripDayWindow> windows = new ArrayList<>(totalDays);
        for (int dayIndex = 0; dayIndex < totalDays; dayIndex++) {
            LocalDate date = startDate.plusDays(dayIndex);
            LocalTime start;
            LocalTime end;
            if (totalDays == 1) {
                start = SINGLE_DAY_START;
                end = SINGLE_DAY_END;
            } else if (dayIndex == 0) {
                start = FIRST_DAY_START;
                end = FIRST_DAY_END;
            } else if (dayIndex == totalDays - 1) {
                start = LAST_DAY_START;
                end = LAST_DAY_END;
            } else {
                start = MIDDLE_DAY_START;
                end = MIDDLE_DAY_END;
            }
            windows.add(new TripDayWindow(dayIndex, date, start, end));
        }
        return windows;
    }
}
