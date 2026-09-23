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

/**
 * 여행 하나에 대한 참여 기록. 회원 소유자는 생성과 동시에 OWNER+READY로 만든다(WORK-03).
 * 비회원 초대 손님(GUEST)은 WORK-04에서 INVITED로 시작해 온보딩 후 READY가 된다 — 이번
 * 범위는 OWNER 생성만 다룬다.
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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    private TripParticipant(
            TripPlan tripPlan, User user, TripParticipantType participantType,
            String displayName, TripParticipantStatus status) {
        this.tripPlan = tripPlan;
        this.user = user;
        this.participantType = participantType;
        this.displayName = displayName;
        this.status = status;
    }

    public static TripParticipant owner(TripPlan tripPlan, User user) {
        return new TripParticipant(
                tripPlan, user, TripParticipantType.OWNER, user.getNickname(), TripParticipantStatus.READY);
    }
}
