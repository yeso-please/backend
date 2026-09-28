package com.yeso.backend.auth.infrastructure;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-at-least-256-bits-long-for-hmac-sha";

    private JwtTokenProvider jwtTokenProvider;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenTtlMinutes(30);
        properties.setRefreshTokenTtlDays(14);

        jwtTokenProvider = new JwtTokenProvider(properties);
        jwtTokenProvider.init();
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("정상 access 토큰은 발급한 userId/email 그대로 파싱된다")
    void generateAccessToken_thenParse_roundTrips() {
        String token = jwtTokenProvider.generateAccessToken(42L, "user@example.com");

        var claims = jwtTokenProvider.parseAccessToken(token).orElseThrow();

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.email()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("서명이 위조된 토큰은 거부한다")
    void parseAccessToken_tamperedSignature_returnsEmpty() {
        String token = jwtTokenProvider.generateAccessToken(1L, "a@example.com");
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThat(jwtTokenProvider.parseAccessToken(tampered)).isEmpty();
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void parseAccessToken_expired_returnsEmpty() {
        Instant now = Instant.now();
        String expired = Jwts.builder()
                .subject("1")
                .claim("email", "a@example.com")
                .claim("type", "access")
                .issuedAt(Date.from(now.minus(1, ChronoUnit.HOURS)))
                .expiration(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();

        assertThat(jwtTokenProvider.parseAccessToken(expired)).isEmpty();
    }

    @Test
    @DisplayName("type claim이 access가 아니면 거부한다")
    void parseAccessToken_wrongType_returnsEmpty() {
        Instant now = Instant.now();
        String wrongType = Jwts.builder()
                .subject("1")
                .claim("email", "a@example.com")
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();

        assertThat(jwtTokenProvider.parseAccessToken(wrongType)).isEmpty();
    }

    @Test
    @DisplayName("임의 문자열(비 JWT)은 거부한다")
    void parseAccessToken_notAJwt_returnsEmpty() {
        assertThat(jwtTokenProvider.parseAccessToken("not-a-real-jwt")).isEmpty();
    }

    @Test
    @DisplayName("opaque refresh 토큰은 256bit 이상이고 매번 다르게 발급된다")
    void generateOpaqueRefreshToken_hasEnoughEntropyAndIsUnique() {
        String first = jwtTokenProvider.generateOpaqueRefreshToken();
        String second = jwtTokenProvider.generateOpaqueRefreshToken();

        byte[] decoded = Base64.getUrlDecoder().decode(first);

        assertThat(decoded.length).isGreaterThanOrEqualTo(32);
        assertThat(Set.of(first, second)).hasSize(2);
    }

    @Test
    @DisplayName("hash()는 SHA-256 hex를 결정적으로 반환한다")
    void hash_isDeterministicSha256() throws Exception {
        String expected = HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest("token-value".getBytes(StandardCharsets.UTF_8)));

        assertThat(jwtTokenProvider.hash("token-value")).isEqualTo(expected);
        assertThat(jwtTokenProvider.hash("token-value")).isEqualTo(jwtTokenProvider.hash("token-value"));
    }
}
