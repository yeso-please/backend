package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 서버에 카카오 키·secret·허용 redirectUri 설정이 없다. 기동은 막지 않고 카카오 로그인 요청만 실패한다. */
public class KakaoNotConfiguredException extends AuthException {
    public KakaoNotConfiguredException() {
        super(ErrorCode.AUTH_KAKAO_NOT_CONFIGURED, "카카오 로그인이 설정되지 않았습니다.");
    }
}
