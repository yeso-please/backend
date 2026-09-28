package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.KakaoLocalRateLimitedException;
import com.yeso.backend.trip.domain.KakaoLocalUnavailableException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 통합 테스트용 카카오 Local fake. 테스트가 {@link #setMode}로 성공·장애·한도 초과를 고르고
 * {@link #setPage}로 돌려줄 결과를 정한다. {@code IntegrationTest}가 매 테스트 후 {@link #reset()}한다.
 */
public class FakeKakaoLocalClient implements KakaoLocalClient {

    public enum Mode { SUCCESS, UNAVAILABLE, RATE_LIMITED }

    private Mode mode = Mode.SUCCESS;
    private Page page = new Page(List.of(), true);
    private final List<Query> queries = new ArrayList<>();

    @Override
    public synchronized Page searchRestaurants(Query query) {
        queries.add(query);
        return switch (mode) {
            case SUCCESS -> page;
            case UNAVAILABLE -> throw new KakaoLocalUnavailableException();
            case RATE_LIMITED -> throw new KakaoLocalRateLimitedException(Duration.ofSeconds(60));
        };
    }

    public synchronized void setMode(Mode mode) {
        this.mode = mode;
    }

    public synchronized void setPage(Page page) {
        this.page = page;
    }

    /** 받은 검색 요청(검색어·기준점·반경·페이지 검증용). */
    public synchronized List<Query> queries() {
        return List.copyOf(queries);
    }

    public synchronized void reset() {
        mode = Mode.SUCCESS;
        page = new Page(List.of(), true);
        queries.clear();
    }
}
