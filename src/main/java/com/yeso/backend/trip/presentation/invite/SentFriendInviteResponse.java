package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.TripFriendInvitation;
import com.yeso.backend.trip.presentation.context.ParticipantSummaryResponse;

import java.time.LocalDateTime;

/** 보낸 친구 초대 목록(4-14)의 원소. 누가 보냈든 참여자 모두에게 같다. */
public record SentFriendInviteResponse(
        Long id, ParticipantSummaryResponse invitee, ParticipantSummaryResponse inviter, String status,
        LocalDateTime createdAt) {

    public static SentFriendInviteResponse from(TripFriendInvitation invitation) {
        return new SentFriendInviteResponse(
                invitation.getId(),
                ParticipantSummaryResponse.from(invitation.getInvitee()),
                ParticipantSummaryResponse.from(invitation.getInviter()),
                invitation.getStatus().name(), invitation.getCreatedAt());
    }
}
