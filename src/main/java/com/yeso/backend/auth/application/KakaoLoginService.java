package com.yeso.backend.auth.application;

import com.yeso.backend.auth.application.AuthService.SocialLogin;
import com.yeso.backend.auth.domain.AuthException;
import com.yeso.backend.auth.domain.KakaoInvalidRedirectUriException;
import com.yeso.backend.auth.domain.KakaoNotConfiguredException;
import com.yeso.backend.auth.domain.SocialProvider;
import com.yeso.backend.auth.infrastructure.KakaoOAuthClient;
import com.yeso.backend.auth.infrastructure.KakaoOAuthClient.KakaoUser;
import com.yeso.backend.auth.infrastructure.KakaoOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 카카오 로그인(docs/api/auth.md 1-6). 카카오 호출(토큰 교환·사용자 정보) 중에는 DB 트랜잭션을 열지 않고,
 * 사용자 정보를 받은 뒤에 {@link AuthService#loginWithSocialAccount}의 트랜잭션에서 회원을 찾거나 만든다.
 * 그래서 이 클래스에는 {@code @Transactional}을 붙이지 않는다.
 *
 * <p>카카오 토큰은 사용자 정보 조회에만 쓰고 저장하지 않는다. 로그에는 실패 사유와 내부 user id만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoLoginService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthProperties properties;
    private final AuthService authService;

    public SocialLogin login(String code, String redirectUri) {
        try {
            verifyRedirectUri(redirectUri);
            String kakaoAccessToken = kakaoOAuthClient.exchangeCode(code, redirectUri);
            KakaoUser kakaoUser = kakaoOAuthClient.fetchUser(kakaoAccessToken);
            SocialLogin result = loginOrRegister(kakaoUser);
            log.info("Kakao login succeeded userId={} newUser={}", result.tokens().user().getId(), result.newUser());
            return result;
        } catch (AuthException e) {
            log.info("Kakao login failed reason={}", e.getErrorCode().code());
            throw e;
        }
    }

    private void verifyRedirectUri(String redirectUri) {
        List<String> allowed = properties.getAllowedRedirectUris();
        if (allowed == null || allowed.stream().allMatch(String::isBlank)) {
            log.warn("Kakao login allowed redirect URIs are not configured");
            throw new KakaoNotConfiguredException();
        }
        if (!allowed.contains(redirectUri)) {
            throw new KakaoInvalidRedirectUriException();
        }
    }

    /** 동시 첫 로그인에서 늦은 쪽은 unique 위반으로 롤백된다. 새 트랜잭션에서 다시 부르면 먼저 만든 회원으로 로그인한다. */
    private SocialLogin loginOrRegister(KakaoUser kakaoUser) {
        try {
            return authService.loginWithSocialAccount(SocialProvider.KAKAO, kakaoUser.id(), kakaoUser.nickname());
        } catch (DataIntegrityViolationException e) {
            log.info("Kakao first login raced with another request; retrying lookup");
            return authService.loginWithSocialAccount(SocialProvider.KAKAO, kakaoUser.id(), kakaoUser.nickname());
        }
    }
}
