package com.yeso.backend.auth.presentation;

import com.yeso.backend.auth.domain.User;

public record UserSummaryResponse(
        Long id,
        String email,
        String nickname,
        String profileImage
) {
    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getNickname(), user.getProfileImage());
    }
}
