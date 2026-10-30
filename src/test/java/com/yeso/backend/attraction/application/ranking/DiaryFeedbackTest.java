package com.yeso.backend.attraction.application.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 여행기 피드백 계산이 ai {@code scripts/diary_feedback_eval.py}(both · asym)와 같은지. */
class DiaryFeedbackTest {

    private static final float R = (float) Math.sqrt(0.5);
    private static final Map<Long, float[]> VECTORS = Map.of(
            1L, new float[]{1, 0, 0}, 2L, new float[]{0, 1, 0}, 3L, new float[]{0.6f, 0.8f, 0},
            4L, new float[]{0, 0, 1}, 5L, new float[]{R, 0, R},
            10L, new float[]{0.8f, 0.6f, 0}, 11L, new float[]{1, 0, 0});
    private static final Map<Long, String> CATEGORY = new HashMap<>(Map.of(
            1L, "A", 2L, "B", 3L, "A", 4L, "C", 5L, "", 10L, "A", 11L, "B"));
    private static final Map<String, Double> PRIOR = Map.of("A", 0.3, "B", 0.5, "C", 0.2);
    private static final Map<Long, Double> BASE = Map.of(1L, 0.5, 2L, 1.5, 3L, -0.2, 4L, 0.1, 5L, 0.9);

    @Test
    @DisplayName("Python 실험과 같은 점수(Rocchio 중심 + 수축한 분류 lift, 같은 지역 후보 안에서 표준화)")
    void parityWithExperiment() {
        Map<Long, Double> out = DiaryFeedback.adjust(BASE, List.of(new DiaryFeedback.Trip((short) 4, List.of(10L, 11L))),
                VECTORS, CATEGORY, PRIOR, 0.3, 5.0);
        Map<Long, Double> expected = Map.of(1L, 0.131904740172, 2L, 1.492551780101, 3L, -1.078280014079,
                4L, -1.15120078825, 5L, 0.605024282057);
        expected.forEach((id, v) -> assertThat(out.get(id)).as("id %d", id).isCloseTo(v, within(1e-6)));
    }

    @Test
    @DisplayName("만족도 가중치는 비대칭: 불만족은 반영을 0으로 줄일 뿐 장소를 밀어내지 않는다")
    void asymmetricWeight() {
        assertThat(DiaryFeedback.weight((short) 5)).isEqualTo(1.0);
        assertThat(DiaryFeedback.weight((short) 3)).isEqualTo(0.5);
        assertThat(DiaryFeedback.weight(null)).isEqualTo(0.5);
        assertThat(DiaryFeedback.weight((short) 1)).isEqualTo(0.0);
        // 가중치 0이면 순위 모델 점수를 그대로 둔다(밀어내는 항이 없다)
        assertThat(DiaryFeedback.adjust(BASE, List.of(new DiaryFeedback.Trip((short) 1, List.of(10L, 11L))),
                VECTORS, CATEGORY, PRIOR, 0.3, 5.0)).isSameAs(BASE);
    }

    @Test
    @DisplayName("여행기가 없거나 λ가 0이면 점수를 바꾸지 않는다")
    void noFeedback() {
        assertThat(DiaryFeedback.adjust(BASE, List.of(), VECTORS, CATEGORY, PRIOR, 0.3, 5.0)).isSameAs(BASE);
        assertThat(DiaryFeedback.adjust(BASE, List.of(new DiaryFeedback.Trip((short) 5, List.of(10L))),
                VECTORS, CATEGORY, PRIOR, 0, 5.0)).isSameAs(BASE);
    }
}
