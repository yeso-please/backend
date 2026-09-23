package com.yeso.backend.onboarding.presentation;

public record OnboardingMeResponse(boolean onboardingCompleted, OnboardingSubmissionResponse submission) {

    public static OnboardingMeResponse notSubmittedYet() {
        return new OnboardingMeResponse(false, null);
    }

    public static OnboardingMeResponse of(OnboardingSubmissionResponse submission) {
        return new OnboardingMeResponse(true, submission);
    }
}
