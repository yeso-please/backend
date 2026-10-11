package com.yeso.backend.trip.infrastructure;

import java.util.List;
import java.util.Optional;

/**
 * 카카오모빌리티 자동차 길찾기 adapter 계약(docs/api/trip.md 5장 {@code travelSource: ROAD}). 실제 연동은
 * {@link HttpKakaoMobilityClient}, 테스트는 fake로 대체한다. 실패는 예외가 아니라 빈 값이다 — 호출한 쪽은 이미 저장된
 * 추정값을 그대로 둔다.
 */
public interface KakaoMobilityClient {

    /**
     * @param points 2~7개 지점(출발, 경유, 도착 순)
     * @return 지점 사이 구간 {@code points.size() - 1}개(순서대로). 실패하면 빈 값
     */
    Optional<List<Leg>> route(List<Point> points);

    record Point(double lat, double lng) {
    }

    record Leg(int distanceMeters, int durationSeconds) {
    }
}
