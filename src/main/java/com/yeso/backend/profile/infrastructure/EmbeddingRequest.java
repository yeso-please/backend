package com.yeso.backend.profile.infrastructure;

import java.util.List;
import java.util.Map;

/** AI 서버에 보내는 구조화 프로필. 자유서술은 AI 서비스가 처리하며 로그에 남기지 않는다. */
public record EmbeddingRequest(String requestId, String modelVersion, int templateVersion, Profile profile) {

    public record Profile(
            String travelMbti,
            String scheduleDensity,
            List<String> experienceTags,
            List<String> excludeTags,
            List<LikedTrip> likedTrips,
            Map<Integer, Integer> travelStyles,
            List<Integer> travelMotives,
            List<String> likedRegions
    ) {}

    public record LikedTrip(String sigCd, String regionName, List<String> tags, String note) {}
}
