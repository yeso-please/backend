package com.yeso.backend.attraction.presentation.region;

import com.yeso.backend.attraction.domain.AttractionCategory;

import java.util.List;

/** 지도 관광지 핀(7-3). 코스에 못 넣는 장소도 {@code recommendable: false}로 포함한다. */
public record AttractionPinsResponse(List<Pin> items, String nextCursor) {

    public record Pin(Long attractionId, String name, String thumbnailUrl, AttractionCategory category,
                      double lat, double lng, boolean recommendable) {
    }
}
