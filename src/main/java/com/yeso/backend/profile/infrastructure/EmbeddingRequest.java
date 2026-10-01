package com.yeso.backend.profile.infrastructure;

/**
 * Python 임베딩 서비스 요청. 문장은 ai가 profile로 합성한다(yeso-please/ai README "백엔드와의 계약").
 * profile(자유서술 메모 포함)은 로그에 남기지 않는다.
 */
public record EmbeddingRequest(String requestId, EmbeddingProfile profile, String modelVersion, int templateVersion) {
}
