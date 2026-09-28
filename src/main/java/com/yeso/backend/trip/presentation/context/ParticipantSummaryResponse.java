package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.auth.domain.User;

public record ParticipantSummaryResponse(Long userId, String nickname) {
    public static ParticipantSummaryResponse from(User user) {
        return new ParticipantSummaryResponse(user.getId(), user.getNickname());
    }
}
