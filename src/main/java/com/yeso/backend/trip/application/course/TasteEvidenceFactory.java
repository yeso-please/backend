package com.yeso.backend.trip.application.course;

import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.trip.infrastructure.DiaryTasteSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 요청자의 추천 근거를 모은다: 최신 설문(profile 조회 계약)과 자기 여행기 취향 신호(trip 안).
 * 코스 생성(5-1)과 교체 후보(5-4)가 같은 근거를 쓰게 한 곳에 둔다.
 */
@Component
@RequiredArgsConstructor
public class TasteEvidenceFactory {

    private final OnboardingQueryService onboardingQueryService;
    private final DiaryTasteSignalRepository diaryTasteSignalRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public TasteEvidence forUser(Long userId) {
        TasteEvidence survey = onboardingQueryService.findLatestTasteAnswers(userId)
                .map(answers -> TasteEvidence.from(answers.travelStyles(), answers.travelMotives()))
                .orElse(TasteEvidence.NONE);
        List<DiarySignalAffinity.Signal> signals = diaryTasteSignalRepository
                .findRecentByAuthor(userId, DiarySignalAffinity.MAX_SIGNALS).stream()
                .map(row -> new DiarySignalAffinity.Signal(row.getSatisfaction(), tags(row.getExperienceTags()),
                        row.getUpdatedAt()))
                .toList();
        return survey.withDiary(DiarySignalAffinity.compute(signals, LocalDateTime.now(clock)));
    }

    private List<String> tags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JacksonException exception) {
            return List.of();
        }
    }
}
