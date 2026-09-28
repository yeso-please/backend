package com.yeso.backend.attraction.presentation.region;

import com.yeso.backend.attraction.domain.IneligibleReason;

import java.util.List;

/**
 * 전국 지역 목록(7-1). {@code days} 없이 부르면 추첨 필드({@code days}·{@code scheduleDensity}·{@code eligibleCount},
 * 지역별 {@code drawEligible}·{@code ineligibleReasons})가 null이다. 필드는 빼지 않는다.
 */
public record RegionListResponse(
        Integer days, String scheduleDensity, Long eligibleCount, List<RegionItem> regions) {

    public record RegionItem(
            String sigCd, String province, String city, Double centerLat, Double centerLng,
            Boolean drawEligible, List<IneligibleReason> ineligibleReasons) {
    }
}
