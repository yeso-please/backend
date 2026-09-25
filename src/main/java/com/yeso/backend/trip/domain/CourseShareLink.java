package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 참여하지 않은 사람에게 코스를 읽기 전용으로 보여주는 링크. 코스는 여행의 일정이므로
 * (course id = trip id) {@link TripPlan}을 공유 대상으로 삼는다.
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
            TripPlan tripPlan, String tokenHash, LocalDateTime expiresAt, User createdByUser) {
        this.tripPlan = tripPlan;
        this.tokenHash = tokenHash;
                this.expiresAt = expiresAt;
        this.createdByUser = createdByUser;
    }

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(LocalDateTime now) {
        this.revokedAt = now;
    }

}
