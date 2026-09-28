package com.yeso.backend.trip.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 식사 슬롯에 고른 식당의 표시 정보(docs/api/trip.md 5장 {@code RestaurantSnapshot}). 외부 응답을 그대로 옮겨
 * 일정을 재현하는 데 쓴다. 원천이 주지 않은 값(대표 메뉴 등)은 만들지 않고 {@code null}로 둔다.
 *
 * @param provider {@code TOUR_API} | {@code FARM_RESTAURANT} | {@code MODEL_RESTAURANT} | {@code GOOD_PRICE} | {@code KAKAO}
 */
public record RestaurantSnapshot(
        String provider,
        String externalId,
        String name,
        String category,
        String address,
        String roadAddress,
        Double lat,
        Double lng,
        String phone,
        String placeUrl,
        String imageUrl,
        String representativeMenu,
        List<String> evidenceLabels,
        List<Source> sources) {

    public RestaurantSnapshot {
        evidenceLabels = evidenceLabels == null ? List.of() : List.copyOf(evidenceLabels);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record Source(String name, String url, LocalDateTime fetchedAt) {
    }
}
