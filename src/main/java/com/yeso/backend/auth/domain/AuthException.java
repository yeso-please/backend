package com.yeso.backend.auth.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

/**
 * auth 도메인 베이스 예외 (docs/conventions/예외-처리.md).
 * 구체적인 예외의 ErrorCode를 GlobalExceptionHandler가 타입 분기 없이 공통 응답으로 변환한다.
 */
public abstract class AuthException extends DomainException {

    protected AuthException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
