package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.CourseShareLink;

import java.time.LocalDateTime;

public record ShareLinkSummaryResponse(
        Long id, String permission, LocalDateTime expiresAt, boolean revoked, LocalDateTime createdAt
) {
    public static ShareLinkSummaryResponse from(CourseShareLink link) {
        return new ShareLinkSummaryResponse(
                link.getId(), link.getPermission().name(), link.getExpiresAt(),
                link.isRevoked(), link.getCreatedAt());
    }
}
