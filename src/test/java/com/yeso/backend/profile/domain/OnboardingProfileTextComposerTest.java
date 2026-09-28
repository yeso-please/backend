package com.yeso.backend.profile.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingProfileTextComposerTest {

    @Test
    @DisplayName("경험 태그는 정렬되고, liked trip은 SIG_CD 오름차순으로 정렬된다")
    void compose_sortsExperienceTagsAndLikedTrips() {
        String text = OnboardingProfileTextComposer.compose(
                "ISFP",
                ScheduleDensity.RELAXED,
                List.of("카페", "바다", "산"),
                List.of("오래 걷기"),
                List.of(
                        new OnboardingProfileTextComposer.LikedTripInput("41110", "좋았어요", List.of("역사")),
                        new OnboardingProfileTextComposer.LikedTripInput("11110", null, List.of())));

        assertThat(text).contains("MBTI: ISFP");
        assertThat(text).contains("일정 밀도: RELAXED");
        assertThat(text).contains("선호 경험: 바다, 산, 카페");
        assertThat(text).contains("제외 조건: 오래 걷기");
        int indexOf11110 = text.indexOf("11110");
        int indexOf41110 = text.indexOf("41110");
        assertThat(indexOf11110).isPositive();
        assertThat(indexOf11110).isLessThan(indexOf41110);
        assertThat(text).contains("41110(역사):좋았어요");
    }

    @Test
    @DisplayName("같은 입력이면 항상 같은 텍스트를 만든다(결정적)")
    void compose_isDeterministic() {
        List<String> experience = List.of("바다", "산");
        List<String> exclude = List.of("물놀이");
        List<OnboardingProfileTextComposer.LikedTripInput> likedTrips = List.of(
                new OnboardingProfileTextComposer.LikedTripInput("11110", "note", List.of("역사")));

        String first = OnboardingProfileTextComposer.compose("ESFP", ScheduleDensity.PACKED, experience, exclude, likedTrips);
        String second = OnboardingProfileTextComposer.compose("ESFP", ScheduleDensity.PACKED, experience, exclude, likedTrips);

        assertThat(first).isEqualTo(second);
    }
}
