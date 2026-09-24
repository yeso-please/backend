package com.yeso.backend.profile.infrastructure;

import java.util.Base64;

/** 테스트 전용 stub. 운영 코드는 절대 이 클래스를 참조하지 않는다(운영 profile에서 fake vector 금지). */
public class FakeEmbeddingClient implements EmbeddingClient {

    public enum Mode { SUCCESS, TRANSIENT, PERMANENT, DIMENSION_MISMATCH }

    private volatile Mode mode = Mode.SUCCESS;

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) throws EmbeddingTransientException, EmbeddingPermanentException {
        return switch (mode) {
            case SUCCESS -> new EmbeddingResult(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}), 384);
            case DIMENSION_MISMATCH -> new EmbeddingResult(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}), 1);
            case TRANSIENT -> throw new EmbeddingTransientException("TIMEOUT", "fake timeout");
            case PERMANENT -> throw new EmbeddingPermanentException("BAD_REQUEST", "fake bad request");
        };
    }
}
