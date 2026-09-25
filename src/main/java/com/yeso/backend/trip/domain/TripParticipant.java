package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.shared.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * 회원 한 명의 여행 참여. 만든 사람(OWNER)과 초대를 수락한 사람(MEMBER)은 동등한 권한이다.
 * 탈퇴하면 이 행을 지우고, 마지막 참여자가 나가면 여행도 지운다(2026-09-24 정책).
 * {@code tripInvitationId}는 어떤 초대 링크로 들어왔는지 추적하는 단순 참조 ID다.
 */
@Entity
@Table(name = "trip_participants")
@Getter
@NoArgsConstructor
public class TripParticipant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan tripPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_type", nullable = false, length = 20)
    private TripParticipantType participantType;

    @Column(name = "trip_invitation_id")
    private Long tripInvitationId;

    private TripParticipant(TripPlan tripPlan, User user, TripParticipantType participantType, Long tripInvitationId) {
        this.tripPlan = tripPlan;
        this.user = user;
        this.participantType = participantType;
        this.tripInvitationId = tripInvitationId;
    }

    public static TripParticipant creator(TripPlan tripPlan, User user) {
        return new TripParticipant(tripPlan, user, TripParticipantType.OWNER, null);
    }

    public static TripParticipant member(TripPlan tripPlan, User user, Long tripInvitationId) {
        return new TripParticipant(tripPlan, user, TripParticipantType.MEMBER, tripInvitationId);
    }

    public boolean isCreator() {
        return participantType == TripParticipantType.OWNER;
    }
}
