package com.yeso.backend.trip.presentation.course;

import com.yeso.backend.trip.domain.RestaurantSnapshot;

import java.util.List;

/** 식당 추천·검색 결과 한 건. {@code RestaurantSnapshot} 필드에 선택 토큰과 기준점에서의 거리를 더한다. */
public record RestaurantCandidateResponse(
        String selectionToken,
        String provider,
        String externalId,
        String name,
        String category,
        String address,
        String roadAddress,
        Double lat,
        Double lng,
        Integer distanceMeters,
        String phone,
        String placeUrl,
        String imageUrl,
        String representativeMenu,
        List<String> evidenceLabels,
        List<RestaurantSnapshot.Source> sources) {

    public static RestaurantCandidateResponse of(String selectionToken, RestaurantSnapshot s, Integer distanceMeters) {
        return new RestaurantCandidateResponse(
                selectionToken, s.provider(), s.externalId(), s.name(), s.category(), s.address(), s.roadAddress(),
                s.lat(), s.lng(), distanceMeters, s.phone(), s.placeUrl(), s.imageUrl(), s.representativeMenu(),
                s.evidenceLabels(), s.sources());
    }
}
