package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.domain.AttractionCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TasteEvidenceTest {

    @Test
    @DisplayName("스타일 1이 1~3이면 자연·산책 근거, 4(중립)·5~7이면 근거가 아니다")
    void natureStyleBoundary() {
        assertThat(TasteEvidence.from(Map.of(1, 3), List.of()).reasonsFor(AttractionCategory.NATURE))
                .containsExactly(TasteEvidence.NATURE_STYLE);
        assertThat(TasteEvidence.from(Map.of(1, 3), List.of()).reasonsFor(AttractionCategory.WALK_REST))
                .containsExactly(TasteEvidence.NATURE_STYLE);
        assertThat(TasteEvidence.from(Map.of(1, 4), List.of())).isSameAs(TasteEvidence.NONE);
        assertThat(TasteEvidence.from(Map.of(1, 5), List.of())).isSameAs(TasteEvidence.NONE);
    }

    @Test
    @DisplayName("스타일 5는 1~3이면 휴식, 5~7이면 체험, 4는 근거가 아니다")
    void restActivityBoundary() {
        assertThat(TasteEvidence.from(Map.of(5, 3), List.of()).reasonsFor(AttractionCategory.WALK_REST))
                .containsExactly(TasteEvidence.REST_STYLE);
        assertThat(TasteEvidence.from(Map.of(5, 5), List.of()).reasonsFor(AttractionCategory.ACTIVITY))
                .containsExactly(TasteEvidence.ACTIVITY_STYLE);
        assertThat(TasteEvidence.from(Map.of(5, 4), List.of())).isSameAs(TasteEvidence.NONE);
    }

    @Test
    @DisplayName("동기 2·6·7·8만 유형과 이어지고, 나머지 동기·스타일 3·6은 근거가 아니다")
    void motives() {
        TasteEvidence evidence = TasteEvidence.from(Map.of(3, 1, 6, 7), List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));

        assertThat(evidence.reasonsFor(AttractionCategory.WALK_REST)).containsExactly("'휴식과 재충전' 여행 동기와 맞아요");
        assertThat(evidence.reasonsFor(AttractionCategory.ACTIVITY))
                .containsExactly("'운동과 건강' 여행 동기와 맞아요", "'새로운 경험' 여행 동기와 맞아요");
        assertThat(evidence.reasonsFor(AttractionCategory.HISTORY_CULTURE)).containsExactly("'역사와 문화 탐방' 여행 동기와 맞아요");
        assertThat(evidence.reasonsFor(AttractionCategory.NATURE)).isEmpty();
        assertThat(evidence.reasonsFor(AttractionCategory.ETC)).isEmpty();
    }

    @Test
    @DisplayName("스타일 근거가 동기 근거보다 앞에 온다")
    void styleBeforeMotive() {
        TasteEvidence evidence = TasteEvidence.from(Map.of(5, 6), List.of(6));

        assertThat(evidence.reasonsFor(AttractionCategory.ACTIVITY))
                .containsExactly(TasteEvidence.ACTIVITY_STYLE, "'운동과 건강' 여행 동기와 맞아요");
    }
}
