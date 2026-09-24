package com.yeso.backend.trip.presentation;

import com.yeso.backend.auth.domain.User;

public record UserSummary(Long userId, String nickname) {
    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getNickname());
    }
}
