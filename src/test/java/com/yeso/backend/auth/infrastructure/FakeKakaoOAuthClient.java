package com.yeso.backend.auth.infrastructure;

import com.yeso.backend.auth.domain.KakaoInvalidCodeException;
import com.yeso.backend.auth.domain.KakaoNotConfiguredException;
import com.yeso.backend.auth.domain.KakaoUnavailableException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 통합 테스트용 카카오 로그인 fake. 테스트가 {@link #register}로 인가 코드마다 돌려줄 카카오 회원을 정하고,
 * {@link #setMode}로 장애·미설정을 고른다. 등록되지 않은 코드는 {@code invalid_grant}처럼 거부한다.
 * 실제 카카오처럼 한 번 쓴 코드는 다시 쓸 수 없다. {@code IntegrationTest}가 매 테스트 후 {@link #reset()}한다.
 */
public class FakeKakaoOAuthClient implements KakaoOAuthClient {

    public enum Mode { SUCCESS, UNAVAILABLE, NOT_CONFIGURED }

    private static final String TOKEN_PREFIX = "fake-kakao-token:";

    private Mode mode = Mode.SUCCESS;
    private final Map<String, KakaoUser> usersByCode = new HashMap<>();
    private final List<String> exchangedCodes = new ArrayList<>();

    @Override
    public synchronized String exchangeCode(String code, String redirectUri) {
        exchangedCodes.add(code);
        switch (mode) {
            case UNAVAILABLE -> throw new KakaoUnavailableException();
            case NOT_CONFIGURED -> throw new KakaoNotConfiguredException();
            default -> {
            }
        }
        if (!usersByCode.containsKey(code)) {
            throw new KakaoInvalidCodeException();
        }
        return TOKEN_PREFIX + code;
    }

    @Override
    public synchronized KakaoUser fetchUser(String accessToken) {
        // 인가 코드는 한 번만 쓸 수 있다 — 사용자 정보를 돌려준 뒤 지운다.
        KakaoUser user = usersByCode.remove(accessToken.substring(TOKEN_PREFIX.length()));
        if (user == null) {
            throw new KakaoUnavailableException();
        }
        return user;
    }

    /** 이 인가 코드를 교환하면 {@code kakaoId}·{@code nickname} 회원을 돌려준다. */
    public synchronized void register(String code, String kakaoId, String nickname) {
        usersByCode.put(code, new KakaoUser(kakaoId, nickname));
    }

    public synchronized void setMode(Mode mode) {
        this.mode = mode;
    }

    /** 토큰 교환을 요청받은 인가 코드(카카오 호출 여부 검증용). */
    public synchronized List<String> exchangedCodes() {
        return List.copyOf(exchangedCodes);
    }

    public synchronized void reset() {
        mode = Mode.SUCCESS;
        usersByCode.clear();
        exchangedCodes.clear();
    }
}
