package com.yeso.backend.profile.infrastructure;

/**
 * Python 임베딩 서비스 adapter 계약. 실제 연동은 {@link HttpEmbeddingClient}가 맡고,
 * 테스트는 이 인터페이스의 fake로 대체한다(docs/conventions/테스트.md).
 */
public interface EmbeddingClient {

    EmbeddingResult embed(EmbeddingRequest request) throws EmbeddingTransientException, EmbeddingPermanentException;
}
