package com.yeso.backend.profile.presentation.onboarding;

import com.yeso.backend.profile.domain.OnboardingQuestionBank;
import com.yeso.backend.profile.domain.OnboardingQuestionBank.TravelStyleQuestion;

import java.util.List;

public record OnboardingQuestionsResponse(
        String questionVersion,
        List<TravelStyleDto> travelStyles,
        List<TravelMotiveDto> travelMotives,
        int maxTravelMotives,
        int maxLikedRegions,
        List<String> excludeTags,
        List<String> scheduleDensityOptions
) {
    public static OnboardingQuestionsResponse current() {
        List<TravelStyleDto> styles = OnboardingQuestionBank.TRAVEL_STYLE_QUESTIONS.values().stream()
                .sorted(java.util.Comparator.comparingInt(TravelStyleQuestion::number))
                .map(TravelStyleDto::from)
                .toList();
        List<TravelMotiveDto> motives = OnboardingQuestionBank.TRAVEL_MOTIVES.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new TravelMotiveDto(entry.getKey(), entry.getValue()))
                .toList();
        return new OnboardingQuestionsResponse(
                OnboardingQuestionBank.QUESTION_VERSION,
                styles,
                motives,
                OnboardingQuestionBank.MAX_TRAVEL_MOTIVES,
                OnboardingQuestionBank.MAX_LIKED_REGIONS,
                OnboardingQuestionBank.EXCLUDE_TAGS,
                List.of("RELAXED", "PACKED"));
    }

    public record TravelStyleDto(
            int number,
            String leftPole,
            String rightPole,
            int minValue,
            int maxValue,
            int neutralValue,
            String evidence
    ) {
        static TravelStyleDto from(TravelStyleQuestion question) {
            return new TravelStyleDto(question.number(), question.leftPole(), question.rightPole(), 1, 7, 4,
                    question.evidence());
        }
    }

    public record TravelMotiveDto(int code, String label) {}
}
