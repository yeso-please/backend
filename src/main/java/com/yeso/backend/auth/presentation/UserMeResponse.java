package com.yeso.backend.auth.presentation;

import com.yeso.backend.auth.domain.User;

public record UserMeResponse(
        Long id,
        String email,
        String nickname,
        String profileImage,
        boolean onboardingCompleted
) {
    public static UserMeResponse of(User user, boolean onboardingCompleted) {
        return new UserMeResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getProfileImage(), onboardingCompleted);
    }
}
