package com.yeso.backend.profile.presentation;

import com.yeso.backend.profile.domain.OnboardingSubmission;

import java.time.LocalDateTime;
import java.util.UUID;

public record OnboardingSubmissionResponse(
        UUID submissionId,
        String questionVersion,
        String mbtiCode,
        String scheduleDensity,
        String profileText,
        String tasteStatus,
        boolean onboardingCompleted,
        LocalDateTime createdAt
) {
    public static OnboardingSubmissionResponse from(OnboardingSubmission submission) {
        return new OnboardingSubmissionResponse(
                submission.getId(),
                submission.getQuestionVersion(),
                submission.getMbtiCode(),
                submission.getScheduleDensity().name(),
                submission.getProfileText(),
                submission.getTasteStatus().name(),
                true,
                submission.getCreatedAt());
    }
}
