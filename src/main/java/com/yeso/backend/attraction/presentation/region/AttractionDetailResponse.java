package com.yeso.backend.attraction.presentation.region;

import com.yeso.backend.attraction.domain.AttractionCategory;

import java.time.LocalDateTime;
import java.util.List;

/** 관광지 상세(7-4). 이미지는 검증된 것만, 운영시간·휴무는 원천 문자열 그대로다. */
public record AttractionDetailResponse(
        Long attractionId, String regionSigCd, String name, AttractionCategory category, String address,
        Double lat, Double lng, String description, List<Image> images, String useTime, String restDate,
        int estimatedDurationMinutes, boolean estimated, boolean recommendable,
        List<NotRecommendableReason> notRecommendableReasons, List<Source> sources) {

    public record Image(String url, String sourceName, String license) {
    }

    public record Source(String name, String contentId, LocalDateTime fetchedAt) {
    }

    public enum NotRecommendableReason {
        MISSING_DESCRIPTION,
        MISSING_IMAGE,
        MISSING_COORDINATE,
        QUALITY_ISSUE
    }
}
