package com.yeso.backend.auth.domain;

import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends AuthException {
    public DuplicateEmailException(String email) {
        super(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다: " + email);
    }
}
