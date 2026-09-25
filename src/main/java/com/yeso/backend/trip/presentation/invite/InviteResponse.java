package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.TripInvitation;

import java.time.LocalDateTime;

/** {@code token} 원문은 발급 응답에서만 반환한다. */
public record InviteResponse(Long id, String token, LocalDateTime expiresAt, LocalDateTime createdAt) {

    public static InviteResponse of(TripInvitation invitation, String token) {
        return new InviteResponse(invitation.getId(), token, invitation.getExpiresAt(), invitation.getCreatedAt());
    }
}
