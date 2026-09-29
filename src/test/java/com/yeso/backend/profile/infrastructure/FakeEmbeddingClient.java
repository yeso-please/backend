package com.yeso.backend.profile.infrastructure;

import java.util.Base64;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchClient;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchRequest;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchResponse;
import com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException;

/**
 * 테스트 전용 stub. 운영 코드는 절대 이 클래스를 참조하지 않는다(운영 profile에서 fake vector 금지).
 * {@code support.TestInfraConfig}가 {@code @Primary} bean으로 등록하고, 테스트는 {@link #setMode}로 장애를 흉내 낸다.
 */
public class FakeEmbeddingClient implements EmbeddingClient, AttractionEmbeddingBatchClient {

    public enum Mode { SUCCESS, TRANSIENT, PERMANENT, DIMENSION_MISMATCH }

    private volatile Mode mode = Mode.SUCCESS;
    private volatile EmbeddingRequest lastRequest;
    private volatile AttractionEmbeddingBatchRequest lastBatchRequest;

    public EmbeddingRequest lastRequest() {
        return lastRequest;
    }

    public AttractionEmbeddingBatchRequest lastBatchRequest() {
        return lastBatchRequest;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** IntegrationTest가 매 테스트 후 호출한다 — 한 테스트가 바꾼 모드가 다음 테스트로 새지 않게. */
    public void reset() {
        this.mode = Mode.SUCCESS;
        this.lastRequest = null;
        this.lastBatchRequest = null;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) throws EmbeddingTransientException, EmbeddingPermanentException {
        this.lastRequest = request;
        return switch (mode) {
            case SUCCESS -> new EmbeddingResult(Base64.getEncoder().encodeToString(new byte[384 * Float.BYTES]), 384);
            case DIMENSION_MISMATCH -> new EmbeddingResult(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}), 1);
            case TRANSIENT -> throw new EmbeddingTransientException("TIMEOUT", "fake timeout");
            case PERMANENT -> throw new EmbeddingPermanentException("BAD_REQUEST", "fake bad request");
        };
    }

    @Override
    public AttractionEmbeddingBatchResponse embedAttractions(AttractionEmbeddingBatchRequest request) {
        this.lastBatchRequest = request;
        if (mode == Mode.TRANSIENT) {
            throw new AttractionEmbeddingServiceException("TIMEOUT", true, null);
        }
        if (mode == Mode.PERMANENT) {
            throw new AttractionEmbeddingServiceException("BAD_REQUEST", false, null);
        }
        int dimension = mode == Mode.DIMENSION_MISMATCH ? 1 : 384;
        byte[] bytes = new byte[dimension * Float.BYTES];
        String vector = Base64.getEncoder().encodeToString(bytes);
        return new AttractionEmbeddingBatchResponse(dimension, request.items().stream()
                .map(item -> new AttractionEmbeddingBatchResponse.Item(item.id(), vector)).toList());
    }
}
