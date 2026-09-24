package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.TripInvitation;

import java.time.LocalDateTime;

/** 목록·조회 응답 — token 원문도 해시도 싣지 않는다. */
public record InviteSummaryResponse(
        Long id, String permission, LocalDateTime expiresAt, boolean revoked, LocalDateTime createdAt) {

    public static InviteSummaryResponse from(TripInvitation invitation) {
        return new InviteSummaryResponse(
                invitation.getId(), invitation.getPermission().name(), invitation.getExpiresAt(),
                invitation.isRevoked(), invitation.getCreatedAt());
    }
}
