package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 통합 테스트용 카카오맵 도보·대중교통 경로 fake. 성공이면 도보 {@link #WALK_SECONDS}초·{@link #WALK_METERS}m,
 * 대중교통 {@link #TRANSIT_SECONDS}초·환승 1회·{@link #TRANSIT_FARE}원을 돌려준다. {@code IntegrationTest}가 매 테스트 후 {@link #reset()}한다.
 */
public class FakeKakaoMapRouteClient implements KakaoMapRouteClient {

    public static final int WALK_METERS = 900;
    public static final int WALK_SECONDS = 720;
    public static final int TRANSIT_METERS = 1500;
    public static final int TRANSIT_SECONDS = 1080;
    public static final int TRANSIT_FARE = 1500;

    private boolean fail;
    private final List<String> calls = new ArrayList<>();

    @Override
    public synchronized Optional<Leg> walk(Point from, Point to) {
        calls.add("WALK");
        return fail ? Optional.empty() : Optional.of(new Leg(WALK_METERS, WALK_SECONDS, path(from, to)));
    }

    @Override
    public synchronized Optional<TransitLeg> transit(Point from, Point to) {
        calls.add("PUBLIC_TRANSIT");
        return fail ? Optional.empty()
                : Optional.of(new TransitLeg(new Leg(TRANSIT_METERS, TRANSIT_SECONDS, path(from, to)), 1, TRANSIT_FARE));
    }

    private static List<Point> path(Point from, Point to) {
        return List.of(from, new Point((from.lat() + to.lat()) / 2, (from.lng() + to.lng()) / 2), to);
    }

    public synchronized void setFail(boolean fail) {
        this.fail = fail;
    }

    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        fail = false;
        calls.clear();
    }
}
