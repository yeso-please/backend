package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.trip.domain.TripFriendInvitation;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.presentation.context.ParticipantSummaryResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 받은 초대 목록(4-7)의 원소. {@code dateConflict}가 true면 프론트는 수락 버튼을 막는다. */
public record ReceivedTripInviteResponse(
        Long id, TripSummary trip, ParticipantSummaryResponse inviter, boolean dateConflict, LocalDateTime createdAt) {

    /**
     * {@code title}은 코스 제목, 없으면 대체 제목이다(3-6과 같음). {@code participants}는 참여자 닉네임(D8),
     * {@code heroImageUrl}은 지역 대표 사진(7-2 {@code heroImage}와 같은 규칙, 지역 전이면 null).
     */
    public record TripSummary(Long tripId, String title, LocalDate startDate, LocalDate endDate, String regionName,
                              String heroImageUrl, List<Participant> participants) {
    }

    public record Participant(String nickname) {
    }

    public static ReceivedTripInviteResponse of(TripFriendInvitation invitation, boolean dateConflict,
                                                List<String> participantNicknames, String heroImageUrl) {
        TripPlan tripPlan = invitation.getTripPlan();
        Region region = tripPlan.getRegion();
        TripSummary trip = new TripSummary(
                tripPlan.getId(), tripPlan.displayTitle(), tripPlan.getStartDate(), tripPlan.getEndDate(),
                region == null ? null : region.getProvince() + " " + region.getCity(), heroImageUrl,
                participantNicknames.stream().map(Participant::new).toList());
        return new ReceivedTripInviteResponse(
                invitation.getId(), trip, ParticipantSummaryResponse.from(invitation.getInviter()), dateConflict,
                invitation.getCreatedAt());
    }
}
