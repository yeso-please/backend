package com.yeso.backend.auth.domain;

import org.springframework.http.HttpStatus;

/**
 * auth 도메인 베이스 예외 (docs/conventions/예외-처리.md).
 * 구체적인 예외마다 HTTP 상태를 함께 가져 GlobalExceptionHandler가 타입 분기 없이 변환한다.
 */
public abstract class AuthException extends RuntimeException {

    private final HttpStatus status;

    protected AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
