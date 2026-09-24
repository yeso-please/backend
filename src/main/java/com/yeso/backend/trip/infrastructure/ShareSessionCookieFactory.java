package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.auth.infrastructure.AuthCookieProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 공유 링크 open 시 URL의 token을 HttpOnly cookie로 교환한다 — 이후 브라우저 히스토리/서버 로그에
 * 원문이 남지 않는다. Secure 속성은 refresh_token cookie와 같은 profile 설정을 따른다.
 */
@Component
@RequiredArgsConstructor
public class ShareSessionCookieFactory {

    public static final String COOKIE_NAME = "share_session";
    private static final String COOKIE_PATH = "/api/shared/courses";
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    private final AuthCookieProperties cookieProperties;

    public Duration sessionTtl() {
        return SESSION_TTL;
    }

    public ResponseCookie create(String sessionToken) {
        return ResponseCookie.from(COOKIE_NAME, sessionToken)
                .httpOnly(true)
                .secure(cookieProperties.isCookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(SESSION_TTL)
                .build();
    }
}
