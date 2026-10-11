package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 서버 허용 목록에 없는 redirectUri다. 카카오를 호출하기 전에 거부한다. */
public class KakaoInvalidRedirectUriException extends AuthException {
    public KakaoInvalidRedirectUriException() {
        super(ErrorCode.AUTH_KAKAO_INVALID_REDIRECT_URI, "허용되지 않은 redirectUri입니다.");
    }
}
