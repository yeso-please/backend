package com.yeso.backend.trip.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TravelTimeEstimatorTest {

    @Test
    @DisplayName("위도 0.1도(약 11.1km)를 자동차로 가면 11.1 × 1.35 ÷ 35 × 60 ≈ 26분이다")
    void car() {
        assertThat(TravelTimeEstimator.distanceKm(35.8, 129.2, 35.9, 129.2)).isCloseTo(11.12, within(0.05));
        assertThat(TravelTimeEstimator.minutes(35.8, 129.2, 35.9, 129.2, Transport.CAR)).isEqualTo(26);
    }

    @Test
    @DisplayName("같은 거리라도 도보가 대중교통보다, 대중교통이 자동차보다 오래 걸린다")
    void byTransport() {
        int walk = TravelTimeEstimator.minutes(35.8, 129.2, 35.82, 129.2, Transport.WALK);
        int transit = TravelTimeEstimator.minutes(35.8, 129.2, 35.82, 129.2, Transport.PUBLIC_TRANSIT);
        int car = TravelTimeEstimator.minutes(35.8, 129.2, 35.82, 129.2, Transport.CAR);

        assertThat(walk).isGreaterThan(transit);
        assertThat(transit).isGreaterThan(car);
    }

    @Test
    @DisplayName("아주 가까워도 최소 5분이다")
    void minimum() {
        assertThat(TravelTimeEstimator.minutes(35.8, 129.2, 35.8, 129.2, Transport.CAR)).isEqualTo(5);
    }
}
