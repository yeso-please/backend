package com.yeso.backend.auth.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 카카오 로그인 설정(docs/api/auth.md 1-6). client secret은 커밋하지 않고
 * {@code ./config/application-secret.yaml} 또는 환경변수로 넣으며 로그에 남기지 않는다.
 * 값이 비어 있어도 기동은 막지 않고 카카오 로그인 요청만 {@code AUTH_KAKAO_NOT_CONFIGURED}로 실패한다.
 */
@Component
@ConfigurationProperties(prefix = "auth.kakao")
@Getter
@Setter
public class KakaoOAuthProperties {

    /** 토큰 교환({@code /oauth/token}) 호스트. */
    private String authBaseUrl = "https://kauth.kakao.com";

    /** 사용자 정보({@code /v2/user/me}) 호스트. */
    private String apiBaseUrl = "https://kapi.kakao.com";

    /** 카카오 앱 REST API 키. 식당 검색과 같은 앱을 쓴다. */
    private String clientId = "";

    /** 카카오 로그인 Client Secret. 카카오 콘솔에서 활성화해야 한다. */
    private String clientSecret = "";

    /**
     * 프론트 콜백 주소 허용 목록. 요청의 {@code redirectUri}와 정확히 같아야 하며(부분 일치·와일드카드 없음)
     * 카카오 콘솔에 등록한 Redirect URI와 같아야 한다. 운영은 HTTPS만 넣는다.
     */
    private List<String> allowedRedirectUris = new ArrayList<>();

    private int connectTimeoutMillis = 2000;

    private int timeoutMillis = 3000;

    public boolean hasCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
