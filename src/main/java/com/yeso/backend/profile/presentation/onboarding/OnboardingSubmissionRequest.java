package com.yeso.backend.profile.presentation.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record OnboardingSubmissionRequest(
        @NotBlank(message = "questionVersion을 입력해주세요.") String questionVersion,
        List<@Valid AnswerRequest> answers,
        // RELAXED/PACKED 외 값은 COMMON_INVALID_REQUEST가 아니라 도메인 코드
        // ONBOARDING_INVALID_SCHEDULE_DENSITY로 응답해야 하므로 enum이 아닌 원시 문자열로 받는다.
        @NotBlank(message = "scheduleDensity를 입력해주세요.") String scheduleDensity,
        List<String> experienceTags,
        List<String> excludeTags,
        List<@Valid LikedTripRequest> likedTrips,
        Map<Integer, Integer> travelStyles,
        List<Integer> travelMotives,
        List<String> likedRegions
) {
    public OnboardingSubmissionRequest {
        if (answers == null) {
            answers = List.of();
        }
        if (experienceTags == null) {
            experienceTags = List.of();
        }
        if (excludeTags == null) {
            excludeTags = List.of();
        }
        if (likedTrips == null) {
            likedTrips = List.of();
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
    }
}
