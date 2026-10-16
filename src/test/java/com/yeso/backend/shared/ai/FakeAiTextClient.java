package com.yeso.backend.shared.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 통합 테스트용 ai 문장 fake. 기본은 응답 없음(= ai 미설정, 규칙 문장)이고, {@link #respond}로 켜면 모든 장소에 LLM 이유를 준다.
 * {@code IntegrationTest}가 매 테스트 후 {@link #reset()}한다.
 */
public class FakeAiTextClient implements AiTextClient {

    private boolean available;
    private final List<Object> requests = new ArrayList<>();

    @Override
    public synchronized Optional<CourseExplanation> explainCourse(CourseExplanationRequest request) {
        requests.add(request);
        if (!available) {
            return Optional.empty();
        }
        List<Reason> reasons = request.places().stream()
                .map(place -> new Reason(place.id(), place.name() + "에서 바람을 느껴 보세요", "llm", "좋아하신 여행과 비슷해요"))
                .toList();
        return Optional.of(new CourseExplanation("AI가 지은 " + request.regionName() + " 여행", "AI가 쓴 소개글이에요.", reasons,
                "llm", "llm", true, "course-explain-v2", "fake-llm"));
    }

    @Override
    public synchronized Optional<RegionMatch> matchRegion(RegionMatchRequest request) {
        requests.add(request);
        if (!available) {
            return Optional.empty();
        }
        return Optional.of(new RegionMatch(request.personaName() + "에게 어울리는 " + request.regionName() + "이에요.", "llm",
                "region-match-v1", "fake-llm"));
    }

    public synchronized void respond(boolean available) {
        this.available = available;
    }

    public synchronized List<Object> requests() {
        return List.copyOf(requests);
    }

    public synchronized void reset() {
        available = false;
        requests.clear();
    }
}
