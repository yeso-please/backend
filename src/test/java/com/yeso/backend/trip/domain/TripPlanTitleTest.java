package com.yeso.backend.trip.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlanTitleTest {

    private static TripPlan trip(LocalDate start, int nights) {
        return new TripPlan(null, start, nights, Transport.WALK, null, null);
    }

    @Test
    @DisplayName("코스 제목이 없으면 'M월 D일부터 N박 N+1일 여행'이다")
    void displayTitle_multiDay() {
        assertThat(trip(LocalDate.of(2028, 1, 9), 2).displayTitle()).isEqualTo("1월 9일부터 2박 3일 여행");
    }

    @Test
    @DisplayName("당일치기는 'M월 D일 당일 여행'이다")
    void displayTitle_dayTrip() {
        assertThat(trip(LocalDate.of(2028, 12, 31), 0).displayTitle()).isEqualTo("12월 31일 당일 여행");
    }

    @Test
    @DisplayName("코스 제목이 있으면 그 제목이다")
    void displayTitle_courseTitle() {
        TripPlan trip = trip(LocalDate.of(2028, 5, 1), 1);
        trip.setTitle("경주, 역사를 따라 걷는 2일");

        assertThat(trip.displayTitle()).isEqualTo("경주, 역사를 따라 걷는 2일");
    }
}
