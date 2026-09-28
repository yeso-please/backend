package com.yeso.backend.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 인증 cookie(refresh 토큰·공유 세션)의 Secure 속성. profile별로 다르다 — local(HTTP)에서는 false,
 * 그 외(운영 등 HTTPS)에서는 true로 application-*.yml에서 override한다. auth·trip이 함께 써서 shared에 둔다.
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class AuthCookieProperties {

    private boolean cookieSecure = true;
}
