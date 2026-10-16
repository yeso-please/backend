package com.yeso.backend.trip.presentation.context;

/**
 * 7-6 성향 맞춤 지역 문장. 요청자에게 MBTI(v2 설문)가 없으면 {@code mbtiCode}·{@code personaName}·{@code text}가 null이다.
 * {@code textSource}: LLM | RULE. {@code aiGenerated}가 true면 화면에 "AI가 작성"을 표시한다.
 */
public record RegionMatchResponse(
        String sigCd, String mbtiCode, String personaName, String text, String textSource, boolean aiGenerated) {
}
