package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 카카오 서버 장애·timeout·예상 밖 응답. 카카오 로그인만 실패하고 다른 로그인은 영향이 없다. */
public class KakaoUnavailableException extends AuthException {
    public KakaoUnavailableException() {
        super(ErrorCode.AUTH_KAKAO_UNAVAILABLE, "카카오 로그인을 잠시 사용할 수 없습니다.");
    }
}
