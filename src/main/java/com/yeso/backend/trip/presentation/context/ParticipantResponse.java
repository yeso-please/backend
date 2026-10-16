package com.yeso.backend.trip.presentation.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeso.backend.trip.domain.TripParticipant;

import java.time.LocalDateTime;

/**
 * 3-8 참여자. {@code isManager}는 참여자를 내보낼 수 있는 사람(생성자, 생성자가 나갔으면 가장 먼저 들어온 참여자, D7·Q7)이다.
 */
public record ParticipantResponse(
        Long userId,
        String nickname,
        @JsonProperty("isCreator") boolean creator,
        @JsonProperty("isManager") boolean manager,
        LocalDateTime joinedAt
) {
    public static ParticipantResponse from(TripParticipant participant, boolean manager) {
        return new ParticipantResponse(
                participant.getUser().getId(), participant.getUser().getNickname(),
                participant.isCreator(), manager, participant.getCreatedAt());
    }
}
