package com.yeso.backend.onboarding.infrastructure;

/**
 * Python 임베딩 서비스 adapter 계약. 실제 연동은 {@link HttpEmbeddingClient}가 맡고,
 * 테스트는 이 인터페이스의 stub/fake로 대체한다(docs/conventions 실행 프롬프트 — "외부 연동은
 * adapter 뒤에 두고 테스트는 stub/fake로 수행한다").
 */
public interface EmbeddingClient {

    EmbeddingResult embed(EmbeddingRequest request) throws EmbeddingTransientException, EmbeddingPermanentException;
}
