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
 * 초대 링크. inviteToken은 refresh_tokens와 같은 원칙으로 해시만 저장한다 —
 * DB 덤프/로그가 노출돼도 원문 토큰(=베어러 크리덴셜) 없이는 재사용할 수 없게.
 * 원문은 발급 시점에 한 번만 응답으로 내려주고 서버는 들고 있지 않는다(서비스 계층 구현 시).
 *
 * revoked로 OWNER의 명시적 무효화를 표현한다 — expiresAt만으로는 재발급 시 기존
 * 링크가 만료 전까지 계속 유효해져 "재발급하면 이전 링크는 못 쓴다"는 요구를 못 지킨다.
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

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TripInvitation(TripPlan tripPlan, String tokenHash, User invitedByUser, LocalDateTime expiresAt) {
        this.tripPlan = tripPlan;
        this.tokenHash = tokenHash;
        this.invitedByUser = invitedByUser;
        this.expiresAt = expiresAt;
    }
}
