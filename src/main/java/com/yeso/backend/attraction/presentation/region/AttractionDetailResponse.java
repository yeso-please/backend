package com.yeso.backend.attraction.presentation.region;

import com.yeso.backend.attraction.domain.AttractionCategory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관광지 상세(7-4). 이미지는 검증된 것만, 운영시간·휴무는 원천 문자열 그대로다.
 * {@code oneLine}·{@code tags}·{@code summaryBasis}는 승인된 한 줄 소개(#89)만, 없으면 {@code null}·빈 배열.
 */
public record AttractionDetailResponse(
        Long attractionId, String regionSigCd, String name, AttractionCategory category, String address,
        Double lat, Double lng, String description, List<Image> images, String useTime, String restDate,
        int estimatedDurationMinutes, boolean estimated, boolean recommendable,
        List<NotRecommendableReason> notRecommendableReasons, List<Source> sources,
        String oneLine, List<String> tags, SummaryBasis summaryBasis) {

    public record Image(String url, String sourceName, String license) {
    }

    public record Source(String name, String contentId, LocalDateTime fetchedAt) {
    }

    /** 원천 설명을 요약했는지, 설명이 없어 이름·분류로만 썼는지(화면에 구분 표시). */
    public enum SummaryBasis {
        SOURCE_SUMMARY,
        NAME_CATEGORY
    }

    public enum NotRecommendableReason {
        MISSING_DESCRIPTION,
        MISSING_IMAGE,
        MISSING_COORDINATE,
        QUALITY_ISSUE
    }
}
