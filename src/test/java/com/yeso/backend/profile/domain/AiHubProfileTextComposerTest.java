package com.yeso.backend.profile.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiHubProfileTextComposerTest {

    @Test
    void composesStableTravelerTextAndSkipsNeutralAndUnsupportedSignals() {
        String text = AiHubProfileTextComposer.compose(
                Map.of(1, 1, 3, 4, 5, 6, 6, 3),
                List.of(2, 7),
                List.of("서울특별시 종로구", "제주특별자치도 제주시"));

        assertThat(text).isEqualTo(
                "자연을 매우 선호, 체험 활동을 꽤 선호, 잘 알려지지 않은 곳을 약간 선호하는 여행자. "
                        + "여행에서 원하는 것은 휴식과 재충전, 새로운 경험. "
                        + "좋아하는 여행지는 서울특별시 종로구, 제주특별자치도 제주시.");
    }

    @Test
    void emptyAnswersProduceNoSyntheticPreference() {
        assertThat(AiHubProfileTextComposer.compose(Map.of(), List.of(), List.of())).isEmpty();
    }
}
