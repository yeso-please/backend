package com.yeso.backend.attraction.presentation.region;

import java.time.LocalDateTime;
import java.util.List;

/** 지역 소개 카드(7-2). 승인된 소개문과 검증된 대표 이미지가 있어야 나온다. */
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
