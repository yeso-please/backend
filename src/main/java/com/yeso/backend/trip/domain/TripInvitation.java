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
 * 참여/온보딩 초대 링크. Share(확정 일정 권한)와는 별도 token 체계다(WORK-04).
 * 원문은 발급 응답에서 한 번만 반환하고 DB에는 SHA-256 해시만 저장한다.
 * 하나의 링크로 여러 손님이 각자 참여할 수 있어 participant와는 1:N이다.
 * {@code permission}은 이 링크로 들어온 손님이 확정 일정에 대해 갖는 기본 권한이다.
 */
@Entity
@Table(name = "trip_invitations")
@Getter
@Setter
@NoArgsConstructor
public class TripInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan tripPlan;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_user_id", nullable = false)
    private User invitedByUser;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SharePermission permission;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TripInvitation(
            TripPlan tripPlan, String tokenHash, User invitedByUser,
            SharePermission permission, LocalDateTime expiresAt) {
        this.tripPlan = tripPlan;
        this.tokenHash = tokenHash;
        this.invitedByUser = invitedByUser;
        this.permission = permission;
        this.expiresAt = expiresAt;
    }

    public void changePermission(SharePermission permission) {
        this.permission = permission;
    }

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }
}
