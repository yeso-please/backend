package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 친구 목록에서 보낸 여행 초대. token 없이 받는 사람의 "받은 초대"에 뜬다(docs/api/trip.md 4-6).
 * 대기 중인 초대는 (여행, 받는 사람)마다 하나이며, 처리되면 PENDING으로 돌아가지 않는다.
 */
@Entity
@Table(name = "trip_friend_invitations")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripFriendInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan tripPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_user_id", nullable = false)
    private User inviter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitee_user_id", nullable = false)
    private User invitee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendInvitationStatus status;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public TripFriendInvitation(TripPlan tripPlan, User inviter, User invitee) {
        this.tripPlan = tripPlan;
        this.inviter = inviter;
        this.invitee = invitee;
        this.status = FriendInvitationStatus.PENDING;
    }

    public boolean isPending() {
        return status == FriendInvitationStatus.PENDING;
    }

    public boolean isFor(Long userId) {
        return invitee.getId().equals(userId);
    }

    public boolean belongsTo(Long tripId) {
        return tripPlan.getId().equals(tripId);
    }

    public void accept(LocalDateTime now) {
        handle(FriendInvitationStatus.ACCEPTED, now);
    }

    public void decline(LocalDateTime now) {
        handle(FriendInvitationStatus.DECLINED, now);
    }

    public void cancel(LocalDateTime now) {
        handle(FriendInvitationStatus.CANCELLED, now);
    }

    private void handle(FriendInvitationStatus next, LocalDateTime now) {
        if (!isPending()) {
            throw new InviteAlreadyHandledException();
        }
        this.status = next;
        this.handledAt = now;
    }
}
