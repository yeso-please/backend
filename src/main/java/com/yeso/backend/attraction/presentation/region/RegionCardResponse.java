package com.yeso.backend.attraction.presentation.region;

import java.time.LocalDateTime;
import java.util.List;

/** 지역 카드(7-2). 소개 콘텐츠가 없으면 지역명과 빈 콘텐츠 목록을 반환한다. */
public record RegionCardResponse(
        String sigCd, String province, String city, String title, List<String> introduction,
        HeroImage heroImage, List<String> characteristics, List<String> historyHighlights,
        List<Landmark> landmarks, List<Source> sources, LocalDateTime updatedAt) {

    public record HeroImage(String url, String sourceName, String sourceUrl, String license) {
    }

    public record Landmark(Long attractionId, String name, String thumbnailUrl) {
    }

    public record Source(String name, String url) {
    }
}
