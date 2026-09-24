package com.yeso.backend.profile.infrastructure;

/** 재시도해도 성공할 수 없는 실패(4xx 형식 오류 등)다 — 즉시 FAILED로 전환한다. */
public class EmbeddingPermanentException extends Exception {

    private final String errorCode;

    public EmbeddingPermanentException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmbeddingPermanentException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
