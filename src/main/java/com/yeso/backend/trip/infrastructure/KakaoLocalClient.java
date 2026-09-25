package com.yeso.backend.trip.infrastructure;

import java.util.List;

/**
 * 카카오 Local 음식점(FD6) 검색 adapter 계약(docs/api/trip.md 5-6). 실제 연동은 {@link HttpKakaoLocalClient},
 * 테스트는 fake로 대체한다(docs/conventions/테스트.md). 장애는 {@code KakaoLocalUnavailableException}(502),
 * 호출 한도는 {@code KakaoLocalRateLimitedException}(503)으로 던진다.
 */
public interface KakaoLocalClient {

    Page searchRestaurants(Query query);

    /**
     * @param keyword 검색어. {@code null}이면 주변 음식점 전체(카테고리 검색)
     * @param page 1~45
     */
    record Query(String keyword, double lat, double lng, int radiusMeters, int page) {
    }

    record Page(List<Place> places, boolean isEnd) {
    }

    /** 카카오 응답의 표시 필드. 맛·평점·대표 메뉴는 카카오가 보장하지 않으므로 담지 않는다. */
    record Place(
            String id, String name, String categoryName, String address, String roadAddress, String phone,
            double lat, double lng, Integer distanceMeters, String placeUrl) {
    }
}
