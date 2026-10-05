package com.yeso.backend.shared.embedding;

/** 관광지 사전 배치 임베딩을 제공하는 외부 서비스 포트. */
public interface AttractionEmbeddingBatchClient {

    AttractionEmbeddingBatchResponse embedAttractions(AttractionEmbeddingBatchRequest request)
            throws AttractionEmbeddingServiceException;
}
