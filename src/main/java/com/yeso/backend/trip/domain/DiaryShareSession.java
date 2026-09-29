package com.yeso.backend.trip.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "diary_share_sessions")
@Getter
@NoArgsConstructor
public class DiaryShareSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "share_link_id", nullable = false)
    private DiaryShareLink shareLink;

    @Column(name = "session_token_hash", nullable = false, unique = true, length = 64)
    private String sessionTokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public DiaryShareSession(DiaryShareLink shareLink, String sessionTokenHash, LocalDateTime expiresAt) {
        this.shareLink = shareLink;
        this.sessionTokenHash = sessionTokenHash;
        this.expiresAt = expiresAt;
    }
}
