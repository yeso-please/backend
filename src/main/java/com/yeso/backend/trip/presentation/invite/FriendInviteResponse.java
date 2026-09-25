package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.TripFriendInvitation;
import com.yeso.backend.trip.presentation.context.ParticipantSummaryResponse;

import java.time.LocalDateTime;

/** 친구 초대 보내기(4-6)의 응답. */
public record FriendInviteResponse(
        Long id, Long tripId, ParticipantSummaryResponse invitee, String status, LocalDateTime createdAt) {

    public static FriendInviteResponse from(TripFriendInvitation invitation) {
        return new FriendInviteResponse(
                invitation.getId(), invitation.getTripPlan().getId(),
                ParticipantSummaryResponse.from(invitation.getInvitee()),
                invitation.getStatus().name(), invitation.getCreatedAt());
    }
}
