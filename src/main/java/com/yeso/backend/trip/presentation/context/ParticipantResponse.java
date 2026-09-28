package com.yeso.backend.trip.presentation.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeso.backend.trip.domain.TripParticipant;

import java.time.LocalDateTime;

public record ParticipantResponse(
        Long userId,
        String nickname,
        @JsonProperty("isCreator") boolean creator,
        LocalDateTime joinedAt
) {
    public static ParticipantResponse from(TripParticipant participant) {
        return new ParticipantResponse(
                participant.getUser().getId(), participant.getUser().getNickname(),
                participant.isCreator(), participant.getCreatedAt());
    }
}
