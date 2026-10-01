package com.yeso.backend.profile.infrastructure;

import java.util.List;

/** 템플릿 1(현재 온보딩) 회원 프로필. JSON 필드명이 ai {@code ProfileIn}과 같아야 한다. */
public record EmbeddingProfile(
        String travelMbti,
        String scheduleDensity,
        List<String> experienceTags,
        List<String> excludeTags,
        List<LikedTrip> likedTrips
) {

    /** note는 자유서술이라 로그에 남기지 않는다. */
    public record LikedTrip(String sigCd, String regionName, List<String> tags, String note) {
    }
}
