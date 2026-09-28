package com.yeso.backend.auth.presentation;

public record AuthResponse(
        UserSummaryResponse user,
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        boolean onboardingCompleted
) {
}
