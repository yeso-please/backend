package com.yeso.backend.auth.infrastructure;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * jwt.secret이 비어 있으면 기동 자체를 막는다. 커밋된 기본값으로 "동작은 하는데
 * 누구나 위조 가능한 서명 키"가 조용히 쓰이는 상황(Copilot 리뷰 지적 사항)을 막기 위함 —
 * application-secret.yaml 또는 환경변수 JWT_SECRET 로 반드시 주입해야 한다.
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    private int accessTokenTtlMinutes;
    private int refreshTokenTtlDays;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 이 비어 있습니다. ./config/application-secret.yaml 또는 "
                            + "환경변수 JWT_SECRET 로 주입하세요(README/application.yml 주석 참고).");
        }
    }
}
