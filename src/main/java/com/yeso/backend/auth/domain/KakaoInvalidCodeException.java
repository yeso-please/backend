package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 카카오가 인가 코드를 거부했다(만료·재사용·위조, `invalid_grant`). */
public class KakaoInvalidCodeException extends AuthException {
    public KakaoInvalidCodeException() {
        super(ErrorCode.AUTH_KAKAO_INVALID_CODE, "카카오 인가 코드가 유효하지 않습니다. 다시 로그인해 주세요.");
    }
}
