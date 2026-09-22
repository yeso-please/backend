package com.yeso.backend.auth.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * refresh 토큰은 응답 body가 아니라 HttpOnly cookie로만 전달한다(WORK-01 계약) —
 * 브라우저 스크립트가 원문을 읽을 수 없게 해 XSS로 인한 탈취 표면을 줄인다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookieFactory {

    public static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";

    private final JwtProperties jwtProperties;
    private final AuthCookieProperties cookieProperties;

    public ResponseCookie create(String refreshToken) {
        return baseBuilder(refreshToken)
                .maxAge(Duration.ofDays(jwtProperties.getRefreshTokenTtlDays()))
                .build();
    }

    public ResponseCookie clear() {
        return baseBuilder("")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieProperties.isCookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH);
    }
}
