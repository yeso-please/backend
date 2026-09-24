package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.TripInvitation;

import java.time.LocalDateTime;

/** 생성 응답 전용 — token 원문은 이때만 반환하고 이후에는 다시 보여주지 않는다. */
public record InviteResponse(
        Long id, String token, String permission, LocalDateTime expiresAt, LocalDateTime createdAt) {

    public static InviteResponse of(TripInvitation invitation, String token) {
        return new InviteResponse(
                invitation.getId(), token, invitation.getPermission().name(),
                invitation.getExpiresAt(), invitation.getCreatedAt());
    }
}
