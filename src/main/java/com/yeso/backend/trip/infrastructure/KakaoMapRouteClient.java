package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;

import java.util.Optional;

/**
 * 카카오맵 도보·대중교통 경로 조회 adapter 계약(docs/api/trip.md 5-7, 2026-10-16 Q8). 실제 연동은
 * {@link HttpKakaoMapRouteClient}, 테스트는 fake로 대체한다. 실패·한도 초과·경로 없음은 예외가 아니라 빈 값이다 —
 * 호출한 쪽은 직선 추정으로 대신한다.
 */
public interface KakaoMapRouteClient {

    /** 두 지점 사이 도보 경로({@code GET /v2/routing/walk}). */
    Optional<Leg> walk(Point from, Point to);

    /** 두 지점 사이 대중교통 경로({@code GET /v2/routing/publictraffic}) 중 첫 번째 추천 경로. */
    Optional<TransitLeg> transit(Point from, Point to);

    /**
     * @param transfers 환승 횟수
     * @param fare      요금(원). 원천이 주지 않으면 null
     */
    record TransitLeg(Leg leg, int transfers, Integer fare) {
    }
}
