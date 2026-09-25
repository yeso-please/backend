package com.yeso.backend.profile.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * 친구 초대 링크. 링크를 만든 사람은 발급으로, 받은 사람은 수락으로 동의하므로 수락하면 바로 친구다.
 * 원문 token은 발급 응답에서 한 번만 주고 DB에는 해시만 둔다. 폐기 전까지 여러 명이 수락할 수 있다.
 */
@Entity
@Table(name = "friend_links")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FriendLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 이 링크로 새로 친구가 된 수. 이미 친구였던 사람의 재수락은 세지 않는다. */
    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public FriendLink(User createdBy, String tokenHash, LocalDateTime expiresAt) {
        this.createdBy = createdBy;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isCreatedBy(Long userId) {
        return createdBy.getId().equals(userId);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke(LocalDateTime now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public void countAcceptance() {
        this.acceptedCount++;
    }
}
