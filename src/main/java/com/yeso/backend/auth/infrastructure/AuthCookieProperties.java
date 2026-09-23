package com.yeso.backend.auth.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * refresh 토큰 cookie의 Secure 속성은 profile별로 다르다 — local(HTTP)에서는 false,
 * 그 외(운영 등 HTTPS)에서는 true로 application-*.yml에서 override한다.
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class AuthCookieProperties {

    private boolean cookieSecure = true;
}
