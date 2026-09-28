package com.yeso.backend.auth.infrastructure;

import com.yeso.backend.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * refresh row를 잠그고 읽는다 — 동시에 같은 토큰으로 들어온 요청 중 하나만
     * rotate에 성공하도록 트랜잭션이 끝날 때까지 다른 트랜잭션의 조회를 대기시킨다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RefreshToken rt where rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * bulk update는 영속성 컨텍스트를 거치지 않으므로 clearAutomatically로 1차 캐시를 비워
     * 같은 트랜잭션 안에서 이미 로드된 엔티티가 폐기 반영 전 상태로 남지 않게 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken rt set rt.revokedAt = :revokedAt
            where rt.familyId = :familyId and rt.revokedAt is null
            """)
    void revokeActiveByFamilyId(@Param("familyId") UUID familyId, @Param("revokedAt") LocalDateTime revokedAt);
}
