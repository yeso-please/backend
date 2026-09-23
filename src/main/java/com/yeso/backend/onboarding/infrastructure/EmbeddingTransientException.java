package com.yeso.backend.onboarding.infrastructure;

/** timeout, 429, 5xx처럼 다시 시도하면 성공할 수 있는 실패다. */
public class EmbeddingTransientException extends Exception {

    private final String errorCode;

    public EmbeddingTransientException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmbeddingTransientException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
