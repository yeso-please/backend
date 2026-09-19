package com.yeso.backend.auth.infrastructure;

import com.yeso.backend.auth.domain.InvalidRefreshTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * access/refresh 토큰 모두 JWT로 발급한다(claim "type"으로 구분).
 * refresh 토큰은 서명 검증과 별개로 DB의 해시 대조·rotate·revoke를 함께 거친다
 * (RefreshTokenRepository, AuthService) — JWT 자체 폐기가 불가능한 한계를 서버측 상태로 보완.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_EMAIL = "email";

    private final JwtProperties jwtProperties;

    private SecretKey key;

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getRefreshTokenTtlDays(), ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
    }

    /** access 토큰이 아니거나(서명/만료 포함) 위조된 경우 empty — 필터에서 조용히 무시하기 위함 */
    public Optional<AccessTokenClaims> parseAccessToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(new AccessTokenClaims(
                    Long.valueOf(claims.getSubject()), claims.get(CLAIM_EMAIL, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** refresh 토큰은 검증 실패 시 컨트롤러까지 명시적인 401로 전파돼야 하므로 예외를 던진다 */
    public Long parseRefreshTokenUserId(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new InvalidRefreshTokenException();
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRefreshTokenException();
        }
    }

    /** refresh_tokens.token_hash에 원문 대신 저장할 SHA-256 해시(docs 설계, RefreshToken 참고) */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    public long accessTokenTtlSeconds() {
        return jwtProperties.getAccessTokenTtlMinutes() * 60L;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public record AccessTokenClaims(Long userId, String email) {
    }
}
