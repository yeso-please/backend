package com.yeso.backend.attraction.presentation.region;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 지역 카드(7-2). 소개 콘텐츠가 없으면 지역명과 빈 콘텐츠 목록을 반환한다.
 * {@code tagline}·{@code tags}는 승인된 지역 한 줄 소개(#89)만, 없으면 {@code null}·빈 배열.
 */
public record RegionCardResponse(
        String sigCd, String province, String city, String title, String tagline, List<String> tags,
        List<String> introduction,
        HeroImage heroImage, List<String> characteristics, List<String> historyHighlights,
        List<Landmark> landmarks, List<Source> sources, LocalDateTime updatedAt) {

    /** {@code attractionId}는 대표 관광지 사진으로 채웠을 때만 있다. */
    public record HeroImage(String url, String sourceName, String sourceUrl, String license, Long attractionId) {
    }

    public record Landmark(Long attractionId, String name, String thumbnailUrl) {
    }

    public record Source(String name, String url) {
    }
}
