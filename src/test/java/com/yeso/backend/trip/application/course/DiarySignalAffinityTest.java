package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.trip.application.course.DiarySignalAffinity.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DiarySignalAffinityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

    @Test
    @DisplayName("만족도 4~5는 1, 3·미입력은 0.5, 1~2는 0이다")
    void satisfactionWeight() {
        assertThat(DiarySignalAffinity.satisfactionWeight((short) 5)).isEqualTo(1.0);
        assertThat(DiarySignalAffinity.satisfactionWeight((short) 4)).isEqualTo(1.0);
        assertThat(DiarySignalAffinity.satisfactionWeight((short) 3)).isEqualTo(0.5);
        assertThat(DiarySignalAffinity.satisfactionWeight(null)).isEqualTo(0.5);
        assertThat(DiarySignalAffinity.satisfactionWeight((short) 2)).isZero();
        assertThat(DiarySignalAffinity.satisfactionWeight((short) 1)).isZero();
    }

    @Test
    @DisplayName("반감기 180일: 0일 1, 180일 0.5, 360일 0.25. 미래 시각은 1")
    void decay() {
        assertThat(DiarySignalAffinity.decay(NOW, NOW)).isEqualTo(1.0);
        assertThat(DiarySignalAffinity.decay(NOW.minusDays(180), NOW)).isCloseTo(0.5, within(1e-9));
        assertThat(DiarySignalAffinity.decay(NOW.minusDays(360), NOW)).isCloseTo(0.25, within(1e-9));
        assertThat(DiarySignalAffinity.decay(NOW.plusDays(1), NOW)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("친화도는 그 유형 태그를 고른 신호의 가중치 비율이다. 최근 신호일수록 무겁다")
    void affinityIsWeightedShare() {
        var result = DiarySignalAffinity.compute(List.of(
                new Signal((short) 5, List.of("산책"), NOW),
                new Signal((short) 5, List.of("역사"), NOW.minusDays(180))), NOW);

        assertThat(result.affinity().get(AttractionCategory.WALK_REST)).isCloseTo(1 / 1.5, within(1e-9));
        assertThat(result.affinity().get(AttractionCategory.HISTORY_CULTURE)).isCloseTo(0.5 / 1.5, within(1e-9));
        assertThat(result.affinity()).doesNotContainKey(AttractionCategory.NATURE);
    }

    @Test
    @DisplayName("같은 유형 태그를 여러 개 골라도 신호 하나로 센다. 시장·로컬 음식은 쓰지 않는다")
    void sameCategoryCountedOnce_andNonAttractionTagsIgnored() {
        var result = DiarySignalAffinity.compute(List.of(
                new Signal((short) 5, List.of("자연", "바다", "산", "시장", "로컬 음식"), NOW)), NOW);

        assertThat(result.affinity()).containsOnlyKeys(AttractionCategory.NATURE);
        assertThat(result.affinity().get(AttractionCategory.NATURE)).isEqualTo(1.0);
        assertThat(result.topTag().get(AttractionCategory.NATURE)).isIn("자연", "바다", "산");
    }

    @Test
    @DisplayName("최근 10건만 쓴다")
    void onlyRecentTen() {
        List<Signal> signals = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            signals.add(new Signal((short) 5, List.of("체험"), NOW));
        }
        signals.add(new Signal((short) 5, List.of("역사"), NOW));

        var result = DiarySignalAffinity.compute(signals, NOW);

        assertThat(result.affinity()).containsOnlyKeys(AttractionCategory.ACTIVITY);
    }

    @Test
    @DisplayName("쓸 수 있는 신호가 없으면 NONE")
    void noUsableSignal() {
        assertThat(DiarySignalAffinity.compute(List.of(), NOW)).isEqualTo(DiarySignalAffinity.Result.NONE);
        assertThat(DiarySignalAffinity.compute(List.of(new Signal((short) 1, List.of("자연"), NOW)), NOW))
                .isEqualTo(DiarySignalAffinity.Result.NONE);
    }
}
