package com.yeso.backend.trip.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 통합 테스트용 카카오모빌리티 fake. 성공이면 모든 구간을 {@link #DISTANCE_METERS}m·{@link #DURATION_SECONDS}초로
 * 돌려준다. {@link #setBeforeRespond}는 응답 직전에 한 번만 실행된다(그사이 다른 편집이 끼는 상황 재현용).
 * {@code IntegrationTest}가 매 테스트 후 {@link #reset()}한다.
 */
public class FakeKakaoMobilityClient implements KakaoMobilityClient {

    public enum Mode { SUCCESS, FAIL }

    public static final int DISTANCE_METERS = 1234;
    public static final int DURATION_SECONDS = 300;

    private Mode mode = Mode.SUCCESS;
    private Runnable beforeRespond = () -> { };
    private final List<List<Point>> requests = new ArrayList<>();

    @Override
    public Optional<List<Leg>> route(List<Point> points) {
        Runnable hook;
        synchronized (this) {
            requests.add(List.copyOf(points));
            hook = beforeRespond;
            beforeRespond = () -> { };
        }
        hook.run();
        synchronized (this) {
            if (mode == Mode.FAIL) {
                return Optional.empty();
            }
        }
        return Optional.of(IntStream.range(1, points.size())
                .mapToObj(i -> new Leg(DISTANCE_METERS, DURATION_SECONDS)).toList());
    }

    public synchronized void setMode(Mode mode) {
        this.mode = mode;
    }

    public synchronized void setBeforeRespond(Runnable beforeRespond) {
        this.beforeRespond = beforeRespond;
    }

    /** 받은 요청의 지점 목록(호출 수·지점 수 검증용). */
    public synchronized List<List<Point>> requests() {
        return List.copyOf(requests);
    }

    public synchronized void reset() {
        mode = Mode.SUCCESS;
        beforeRespond = () -> { };
        requests.clear();
    }
}
