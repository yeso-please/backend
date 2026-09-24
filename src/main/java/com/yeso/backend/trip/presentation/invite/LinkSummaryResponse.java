package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.CourseShareLink;
import com.yeso.backend.trip.domain.TripInvitation;
import com.yeso.backend.trip.presentation.UserSummary;

import java.time.LocalDateTime;

/** 초대·공유 링크 목록의 원소. token 원문은 싣지 않는다. */
public record LinkSummaryResponse(
        Long id, LocalDateTime expiresAt, boolean revoked, UserSummary createdBy, LocalDateTime createdAt) {

    public static LinkSummaryResponse from(TripInvitation invitation) {
        return new LinkSummaryResponse(
                invitation.getId(), invitation.getExpiresAt(), invitation.isRevoked(),
                UserSummary.from(invitation.getInvitedByUser()), invitation.getCreatedAt());
    }

    public static LinkSummaryResponse from(CourseShareLink link) {
        return new LinkSummaryResponse(
                link.getId(), link.getExpiresAt(), link.isRevoked(),
                UserSummary.from(link.getCreatedByUser()), link.getCreatedAt());
    }
}
