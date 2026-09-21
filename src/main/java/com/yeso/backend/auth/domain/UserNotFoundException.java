package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class UserNotFoundException extends AuthException {
    public UserNotFoundException(Long userId) {
        super(ErrorCode.USER_NOT_FOUND, "사용자를 찾을 수 없습니다.");
    }
}
