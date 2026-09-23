package com.yeso.backend.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 비회원 참여자가 가입 없이 이후 요청(온보딩 제출 등)을 인증하는 opaque bearer. 참여자당 하나다. */
@Entity
@Table(name = "guest_sessions")
@Getter
@Setter
@NoArgsConstructor
public class GuestSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false, unique = true)
    private TripParticipant participant;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public GuestSession(TripParticipant participant, String tokenHash, LocalDateTime expiresAt) {
        this.participant = participant;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
