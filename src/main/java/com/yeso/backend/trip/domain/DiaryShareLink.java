package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "diary_share_links")
@Getter
@NoArgsConstructor
public class DiaryShareLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "diary_id", nullable = false)
    private TravelDiary diary;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public DiaryShareLink(TravelDiary diary, String tokenHash, LocalDateTime expiresAt, User createdBy) {
        this.diary = diary;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public boolean activeAt(LocalDateTime now) { return revokedAt == null && expiresAt.isAfter(now); }
    public boolean revoked() { return revokedAt != null; }
    public void revoke(LocalDateTime now) { if (revokedAt == null) revokedAt = now; }
}
