package com.yeso.backend.trip.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlanOverlapTest {

    private static TripPlan tripOf(LocalDate start, LocalDate end) {
        return new TripPlan(null, start, (int) java.time.temporal.ChronoUnit.DAYS.between(start, end), Transport.WALK, null, null);
    }

    @Test
    @DisplayName("새 일정이 기존 일정을 완전히 포함하면 겹친다")
    void overlaps_newContainsExisting() {
        TripPlan existing = tripOf(LocalDate.of(2028, 6, 5), LocalDate.of(2028, 6, 7));

        assertThat(existing.overlaps(LocalDate.of(2028, 6, 1), LocalDate.of(2028, 6, 10))).isTrue();
    }

    @Test
    @DisplayName("기존 일정이 새 일정을 완전히 포함하면 겹친다")
    void overlaps_existingContainsNew() {
        TripPlan existing = tripOf(LocalDate.of(2028, 6, 1), LocalDate.of(2028, 6, 10));

        assertThat(existing.overlaps(LocalDate.of(2028, 6, 5), LocalDate.of(2028, 6, 7))).isTrue();
    }

    @Test
    @DisplayName("새 일정이 기존 일정 시작 쪽과 겹치면 겹친다")
    void overlaps_partialAtStart() {
        TripPlan existing = tripOf(LocalDate.of(2028, 6, 5), LocalDate.of(2028, 6, 10));

        assertThat(existing.overlaps(LocalDate.of(2028, 6, 1), LocalDate.of(2028, 6, 5))).isTrue();
    }

    @Test
    @DisplayName("새 일정이 기존 일정 끝 쪽과 겹치면 겹친다(양끝 포함)")
    void overlaps_partialAtEnd() {
        TripPlan existing = tripOf(LocalDate.of(2028, 6, 1), LocalDate.of(2028, 6, 5));

        assertThat(existing.overlaps(LocalDate.of(2028, 6, 5), LocalDate.of(2028, 6, 10))).isTrue();
    }

    @Test
    @DisplayName("완전히 다른 기간이면 겹치지 않는다")
    void overlaps_noOverlap_returnsFalse() {
        TripPlan existing = tripOf(LocalDate.of(2028, 6, 1), LocalDate.of(2028, 6, 5));

        assertThat(existing.overlaps(LocalDate.of(2028, 6, 6), LocalDate.of(2028, 6, 10))).isFalse();
    }
}
