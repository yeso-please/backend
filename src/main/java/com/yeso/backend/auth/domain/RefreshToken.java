package com.yeso.backend.auth.domain;

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
import java.util.UUID;

/**
 * refresh 토큰은 원문이 아니라 해시로 저장한다 — DB가 노출돼도 토큰을 재사용할 수 없게.
 *
 * {@code familyId}는 최초 발급부터 이어지는 rotate 체인을 묶는다. 이미 폐기된
 * (rotate로 대체된) 토큰이 다시 제시되면 탈취로 간주해 같은 family의 미만료 토큰을
 * 전부 폐기한다(WORK-01 계약, docs/mvp/implementation-workpack.md).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "replaced_by_token_id")
    private Long replacedByTokenId;

    public RefreshToken(User user, String tokenHash, UUID familyId, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke(LocalDateTime now) {
        this.revokedAt = now;
    }

    public void revokeAsReplaced(LocalDateTime now, Long replacementId) {
        this.revokedAt = now;
        this.replacedByTokenId = replacementId;
    }
}
