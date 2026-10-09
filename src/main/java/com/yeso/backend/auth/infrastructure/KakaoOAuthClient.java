package com.yeso.backend.auth.infrastructure;

/**
 * 카카오 로그인 adapter 계약(docs/api/auth.md 1-6). 실제 연동은 {@link HttpKakaoOAuthClient},
 * 테스트는 fake로 대체한다(docs/conventions/테스트.md). 인가 코드 거부는 {@code KakaoInvalidCodeException}(401),
 * 장애는 {@code KakaoUnavailableException}(502), 키·secret 미설정은 {@code KakaoNotConfiguredException}(503)으로 던진다.
 */
public interface KakaoOAuthClient {

    /** 인가 코드를 카카오 access token으로 바꾼다. 코드는 한 번만 쓸 수 있어 재시도하지 않는다. */
    String exchangeCode(String code, String redirectUri);

    KakaoUser fetchUser(String accessToken);

    /**
     * @param id 카카오 회원번호(앱별 고유)
     * @param nickname 동의하지 않았거나 비어 있으면 {@code null}
     */
    record KakaoUser(String id, String nickname) {
    }
}
