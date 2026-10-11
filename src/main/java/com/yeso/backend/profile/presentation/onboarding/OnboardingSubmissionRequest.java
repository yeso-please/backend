package com.yeso.backend.profile.presentation.onboarding;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record OnboardingSubmissionRequest(
        @NotBlank(message = "questionVersion을 입력해주세요.") String questionVersion,
        // RELAXED/PACKED 외 값은 COMMON_INVALID_REQUEST가 아니라 도메인 코드
        // ONBOARDING_INVALID_SCHEDULE_DENSITY로 응답해야 하므로 enum이 아닌 원시 문자열로 받는다.
        @NotBlank(message = "scheduleDensity를 입력해주세요.") String scheduleDensity,
        List<String> excludeTags,
        Map<Integer, Integer> travelStyles,
        List<Integer> travelMotives,
        List<String> likedRegions,
        // v2 여행 MBTI 문항 번호(1~12) → 선택(1 또는 2). v1이면 무시한다.
        Map<Integer, Integer> mbtiAnswers
) {
    public OnboardingSubmissionRequest {
        if (excludeTags == null) {
            excludeTags = List.of();
        }
        if (travelStyles == null) {
            travelStyles = Map.of();
        }
        if (travelMotives == null) {
            travelMotives = List.of();
        }
        if (likedRegions == null) {
            likedRegions = List.of();
        }
        if (mbtiAnswers == null) {
            mbtiAnswers = Map.of();
        }
    }
}
