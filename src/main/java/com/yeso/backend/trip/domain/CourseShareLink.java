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
 * 확정 일정 공유 링크. "course"라는 별도 엔티티는 아직 없어(WORK-06/07/08 이전) 확정된
 * {@link TripPlan} 자체를 공유 대상으로 삼는다 — 표에 남은 컬럼명(`course_share_links`,
 * `trip_plan_id`)이 이 사실을 그대로 보여준다.
 */
@Entity
@Table(name = "course_share_links")
@Getter
@Setter
@NoArgsConstructor
public class CourseShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan tripPlan;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SharePermission permission;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public CourseShareLink(
            TripPlan tripPlan, String tokenHash, SharePermission permission,
            LocalDateTime expiresAt, User createdByUser) {
        this.tripPlan = tripPlan;
        this.tokenHash = tokenHash;
        this.permission = permission;
        this.expiresAt = expiresAt;
        this.createdByUser = createdByUser;
    }

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }

    public void changePermission(SharePermission permission) {
        this.permission = permission;
    }
}
