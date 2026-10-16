package com.yeso.backend.shared.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * ai 서버의 생성형 문장 API(Gemini). 표시용 문장만 만든다 — 추천 계산에는 쓰지 않는다.
 * 실패·timeout·미설정이면 빈 값을 돌려주고 호출한 쪽이 규칙 문장을 쓴다. 회원 설문 답·메모는 보내지 않는다.
 */
public interface AiTextClient {

    /** ai {@code POST /explanations}: 코스 제목·소개·장소별 이유(trip 5-1, D16). */
    Optional<CourseExplanation> explainCourse(CourseExplanationRequest request);

    /** ai {@code POST /explanations/region-match}: 성향에 맞춘 지역 소개 2~3문장(7-6, D13-3). */
    Optional<RegionMatch> matchRegion(RegionMatchRequest request);

    record CourseExplanationRequest(String requestId, String regionName, int days, List<Place> places) {
    }

    /** {@code id}는 코스 항목 ID(a-…). 관광지 공개 데이터만 담는다. */
    record Place(String id, String name, String lclsSystm1, String lclsSystm2, String lclsSystm3, String regionName,
                 String description, int day, int order, List<String> matchedFeatures) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CourseExplanation(String title, String intro, List<Reason> reasons, String titleSource, String introSource,
                             boolean aiGenerated, String promptVersion, String generatorModel) {
    }

    /** {@code source}: llm | rule. {@code personalReason}은 취향 기준 회원 본인에게만 보인다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reason(String id, String reason, String source, String personalReason) {
    }

    record RegionMatchRequest(String requestId, String personaName, String personaSummary, List<String> personaLikes,
                              String regionName, String tagline, List<String> tags, List<Landmark> landmarks) {
    }

    record Landmark(String name, String oneLine, String category) {
    }

    /** {@code source}: llm | rule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionMatch(String text, String source, String promptVersion, String generatorModel) {
    }
}
