package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
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
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 여행 하나에 대한 참여 기록. 회원 소유자는 생성과 동시에 OWNER+READY로 만든다(WORK-03).
 * 비회원 초대 손님(GUEST)은 초대 링크로 참여할 때 INVITED로 시작해 표시 이름을 정하고 온보딩을
 * 마치면 READY가 된다(WORK-04). {@code tripInvitationId}는 어떤 초대 링크로 들어왔는지 추적하는
 * 단순 참조 ID다.
 */
@Entity
@Table(name = "trip_participants")
@Getter
@Setter
@NoArgsConstructor
public class TripParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan tripPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_type", nullable = false, length = 20)
    private TripParticipantType participantType;

    @Column(name = "display_name", nullable = false, length = 30)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripParticipantStatus status;

    @Column(name = "trip_invitation_id")
    private Long tripInvitationId;

    @Column(name = "latest_onboarding_submission_id")
    private UUID latestOnboardingSubmissionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    private TripParticipant(
            TripPlan tripPlan, User user, TripParticipantType participantType,
            String displayName, TripParticipantStatus status, Long tripInvitationId) {
        this.tripPlan = tripPlan;
        this.user = user;
        this.participantType = participantType;
        this.displayName = displayName;
        this.status = status;
        this.tripInvitationId = tripInvitationId;
    }

    public static TripParticipant owner(TripPlan tripPlan, User user) {
        return new TripParticipant(
                tripPlan, user, TripParticipantType.OWNER, user.getNickname(), TripParticipantStatus.READY, null);
    }

    public static TripParticipant guest(TripPlan tripPlan, Long tripInvitationId, String displayName) {
        return new TripParticipant(
                tripPlan, null, TripParticipantType.GUEST, displayName, TripParticipantStatus.INVITED,
                tripInvitationId);
    }

    public void startOnboarding() {
        this.status = TripParticipantStatus.ONBOARDING;
        this.updatedAt = LocalDateTime.now();
    }

    /** 임베딩이 PENDING이어도 온보딩 자체를 마쳤으면 READY다. vector 준비 여부는 점수 계산에서 따진다. */
    public void completeOnboarding(UUID submissionId) {
        this.latestOnboardingSubmissionId = submissionId;
        this.status = TripParticipantStatus.READY;
        this.updatedAt = LocalDateTime.now();
    }

    public void revoke() {
        this.status = TripParticipantStatus.REVOKED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isGuest() {
        return participantType == TripParticipantType.GUEST;
    }
}
