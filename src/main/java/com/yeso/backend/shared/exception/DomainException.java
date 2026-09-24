package com.yeso.backend.shared.exception;

import java.util.Map;

public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    protected DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    /** 클라이언트가 재시도/UI 분기에 쓸 구조화된 부가 정보(예: 겹치는 여행 목록)가 있을 때 사용한다. */
    protected DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
