package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidRefreshTokenException extends AuthException {
    public InvalidRefreshTokenException() {
        super(ErrorCode.INVALID_REFRESH_TOKEN, "인증이 만료되었습니다. 다시 로그인해주세요.");
    }
}
