package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.shared.config.AuthCookieProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class DiaryShareSessionCookieFactory {
    public static final String COOKIE_NAME = "diary_share_session";
    private static final Duration TTL = Duration.ofHours(2);
    private final AuthCookieProperties cookieProperties;

    public ResponseCookie create(String token) {
        return ResponseCookie.from(COOKIE_NAME, token).httpOnly(true).secure(cookieProperties.isCookieSecure())
                .sameSite("Lax").path("/api/shared/diaries").maxAge(TTL).build();
    }
}
