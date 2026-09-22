package com.yeso.backend.auth.infrastructure;

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
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * access 토큰은 서명된 JWT(claim "type"="access")로 발급해 서명 검증만으로 매 요청을 처리한다.
 * refresh 토큰은 256 bit 이상 CSPRNG opaque 값이다 — 클레임 위조 우려가 없고, DB의
 * 해시 대조·family 회전·재사용 탐지(RefreshTokenRepository, AuthService)만으로 폐기를 완전히 제어한다
 * (docs/adr/0002-opaque-refresh-token-rotation.md).
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String CLAIM_EMAIL = "email";
    private static final int REFRESH_TOKEN_BYTES = 32; // 256 bit

    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

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
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** 256 bit 이상 URL-safe opaque 토큰. 원문은 응답에서 한 번만 노출하고 DB에는 해시만 저장한다. */
    public String generateOpaqueRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    /** refresh_tokens.token_hash에 원문 대신 저장할 SHA-256 해시 */
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
