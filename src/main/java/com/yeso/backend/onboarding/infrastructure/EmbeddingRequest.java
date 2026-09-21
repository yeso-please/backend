package com.yeso.backend.onboarding.infrastructure;

/** Python 임베딩 서비스 요청. text(profileText 등 자유서술 포함 가능)는 로그에 남기지 않는다. */
public record EmbeddingRequest(String requestId, String text, String modelVersion, int templateVersion) {
}
