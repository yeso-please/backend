package com.yeso.backend.onboarding.presentation;

import com.yeso.backend.onboarding.domain.OnboardingQuestion;
import com.yeso.backend.onboarding.domain.OnboardingQuestionBank;

import java.util.List;

public record OnboardingQuestionsResponse(
        String questionVersion,
        List<QuestionDto> questions,
        List<String> experienceTags,
        int maxExperienceTags,
        List<String> excludeTags,
        List<String> scheduleDensityOptions
) {
    public static OnboardingQuestionsResponse current() {
        List<QuestionDto> questions = OnboardingQuestionBank.QUESTIONS.stream()
                .map(QuestionDto::from)
                .toList();
        return new OnboardingQuestionsResponse(
                OnboardingQuestionBank.QUESTION_VERSION,
                questions,
                OnboardingQuestionBank.EXPERIENCE_TAGS,
                OnboardingQuestionBank.MAX_EXPERIENCE_TAGS,
                OnboardingQuestionBank.EXCLUDE_TAGS,
                List.of("RELAXED", "PACKED"));
    }

    public record QuestionDto(int number, String axis, String prompt, ChoiceDto choice1, ChoiceDto choice2) {
        static QuestionDto from(OnboardingQuestion question) {
            return new QuestionDto(
                    question.number(),
                    question.axis().name(),
                    question.prompt(),
                    new ChoiceDto(1, question.choice1Text(), String.valueOf(question.choice1Letter())),
                    new ChoiceDto(2, question.choice2Text(), String.valueOf(question.choice2Letter())));
        }
    }

    public record ChoiceDto(int choice, String text, String letter) {
    }
}
