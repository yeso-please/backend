package com.yeso.backend.auth.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCookieFactoryTest {

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("unit-test-secret");
        properties.setAccessTokenTtlMinutes(30);
        properties.setRefreshTokenTtlDays(14);
        return properties;
    }

    private AuthCookieProperties cookieProperties(boolean secure) {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setCookieSecure(secure);
        return properties;
    }

    @Test
    @DisplayName("운영/dev-rds profile(secure=true)이면 cookie에 Secure가 붙는다")
    void create_secureProfile_setsSecureAttribute() {
        RefreshTokenCookieFactory factory = new RefreshTokenCookieFactory(jwtProperties(), cookieProperties(true));

        ResponseCookie cookie = factory.create("opaque-refresh-token");

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    @DisplayName("local profile(secure=false)이면 cookie에 Secure가 붙지 않는다")
    void create_localProfile_omitsSecureAttribute() {
        RefreshTokenCookieFactory factory = new RefreshTokenCookieFactory(jwtProperties(), cookieProperties(false));

        ResponseCookie cookie = factory.create("opaque-refresh-token");

        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("clear()는 즉시 만료되는 빈 cookie를 만든다")
    void clear_producesImmediatelyExpiringCookie() {
        RefreshTokenCookieFactory factory = new RefreshTokenCookieFactory(jwtProperties(), cookieProperties(true));

        ResponseCookie cookie = factory.clear();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }
}
