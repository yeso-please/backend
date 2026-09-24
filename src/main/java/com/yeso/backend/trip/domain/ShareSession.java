package com.yeso.backend.trip.domain;

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
 * 공유 링크 open 시 URL의 원문 token을 교환해 발급하는 짧은 HttpOnly 세션.
 * 교환 뒤에는 token 없는 URL로 303 redirect하고, 이후 조회는 이 세션으로만 한다.
 */
@Entity
@Table(name = "share_sessions")
@Getter
@Setter
@NoArgsConstructor
public class ShareSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_link_id", nullable = false)
    private CourseShareLink shareLink;

    @Column(name = "session_token_hash", nullable = false, unique = true)
    private String sessionTokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ShareSession(CourseShareLink shareLink, String sessionTokenHash, LocalDateTime expiresAt) {
        this.shareLink = shareLink;
        this.sessionTokenHash = sessionTokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(LocalDateTime now) {
        return expiresAt.isAfter(now);
    }
}
