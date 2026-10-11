package com.yeso.backend.profile.infrastructure;

import java.util.List;
import java.util.Map;

/** AI 서버에 보내는 구조화 프로필(템플릿 2). 문장 합성은 AI 서비스가 한다. 여행 MBTI는 표시용이라 보내지 않는다. */
public record EmbeddingRequest(String requestId, String modelVersion, int templateVersion, Profile profile) {

    public record Profile(
            String scheduleDensity,
            List<String> excludeTags,
            Map<Integer, Integer> travelStyles,
            List<Integer> travelMotives,
            List<String> likedRegions
    ) {}
}
