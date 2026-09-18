package com.yeso.backend.auth.domain;

import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends AuthException {
    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다. 다시 로그인해주세요.");
    }
}
