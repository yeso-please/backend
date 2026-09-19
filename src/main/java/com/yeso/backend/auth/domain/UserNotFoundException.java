package com.yeso.backend.auth.domain;

import org.springframework.http.HttpStatus;

public class UserNotFoundException extends AuthException {
    public UserNotFoundException(Long userId) {
        super(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다: " + userId);
    }
}
