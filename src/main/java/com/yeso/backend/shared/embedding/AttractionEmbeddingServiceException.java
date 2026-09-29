package com.yeso.backend.shared.embedding;

/** 배치 호출 실패. 일시 장애면 호출자가 실행을 중단하고 다음 실행에서 이어간다. */
public class AttractionEmbeddingServiceException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public AttractionEmbeddingServiceException(String errorCode, boolean retryable, Throwable cause) {
        super("관광지 임베딩 서비스 호출 실패: " + errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
