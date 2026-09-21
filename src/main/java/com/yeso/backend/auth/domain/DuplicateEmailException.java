package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class DuplicateEmailException extends AuthException {
    public DuplicateEmailException(String email) {
        super(ErrorCode.DUPLICATE_EMAIL, "이미 사용 중인 이메일입니다.");
    }
}
