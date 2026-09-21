package com.yeso.backend.trip.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripDayWindowCalculatorTest {

    @Test
    @DisplayName("당일(0박)은 12:00~20:00 하루만 반환한다")
    void calculate_zeroNights_returnsSingleDayWindow() {
        List<TripDayWindow> windows = TripDayWindowCalculator.calculate(LocalDate.of(2028, 6, 1), 0);

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).windowStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(windows.get(0).windowEnd()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("다일 여행은 첫날 12~20, 중간 09~20, 마지막 날 09~17이다")
    void calculate_multiNight_returnsFirstMiddleLastWindows() {
        List<TripDayWindow> windows = TripDayWindowCalculator.calculate(LocalDate.of(2028, 6, 1), 3);

        assertThat(windows).hasSize(4);
        assertThat(windows.get(0).windowStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(windows.get(0).windowEnd()).isEqualTo(LocalTime.of(20, 0));
        assertThat(windows.get(1).windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(windows.get(1).windowEnd()).isEqualTo(LocalTime.of(20, 0));
        assertThat(windows.get(2).windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(windows.get(2).windowEnd()).isEqualTo(LocalTime.of(20, 0));
        assertThat(windows.get(3).windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(windows.get(3).windowEnd()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("1박(첫날=마지막날 다음날)은 첫날 12~20, 마지막 날 09~17이다")
    void calculate_oneNight_hasNoMiddleDay() {
        List<TripDayWindow> windows = TripDayWindowCalculator.calculate(LocalDate.of(2028, 6, 1), 1);

        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).windowStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(windows.get(1).windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(windows.get(1).windowEnd()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("윤년 2월을 지나는 일정도 날짜가 정확히 이어진다")
    void calculate_acrossLeapDay_datesAreContinuous() {
        // 2028은 윤년이라 2월이 29일까지 있다.
        List<TripDayWindow> windows = TripDayWindowCalculator.calculate(LocalDate.of(2028, 2, 28), 2);

        assertThat(windows).extracting(TripDayWindow::date).containsExactly(
                LocalDate.of(2028, 2, 28), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 1));
    }

    @Test
    @DisplayName("연말을 지나는 일정도 날짜가 정확히 이어진다")
    void calculate_acrossYearEnd_datesAreContinuous() {
        List<TripDayWindow> windows = TripDayWindowCalculator.calculate(LocalDate.of(2028, 12, 30), 2);

        assertThat(windows).extracting(TripDayWindow::date).containsExactly(
                LocalDate.of(2028, 12, 30), LocalDate.of(2028, 12, 31), LocalDate.of(2029, 1, 1));
    }
}
