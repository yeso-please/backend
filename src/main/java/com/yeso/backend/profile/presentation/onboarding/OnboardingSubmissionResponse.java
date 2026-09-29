package com.yeso.backend.profile.presentation.onboarding;

import com.yeso.backend.profile.domain.OnboardingSubmission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OnboardingSubmissionResponse(
        UUID submissionId,
        String questionVersion,
        String mbtiCode,
        String scheduleDensity,
        String profileText,
        Map<Integer, Integer> travelStyles,
        List<Integer> travelMotives,
        List<String> likedRegions,
        String tasteStatus,
        boolean onboardingCompleted,
        LocalDateTime createdAt
) {
    public static OnboardingSubmissionResponse from(OnboardingSubmission submission, List<String> likedRegions) {
        return new OnboardingSubmissionResponse(
                submission.getId(),
                submission.getQuestionVersion(),
                submission.getMbtiCode(),
                submission.getScheduleDensity().name(),
                submission.getProfileText(),
                submission.getTravelStyles(),
                submission.getTravelMotives(),
                likedRegions,
                submission.getTasteStatus().name(),
                true,
                submission.getCreatedAt());
    }
}
