package com.yeso.backend.onboarding.domain;

import java.util.Map;

/**
 * 축별 선택 글자 개수를 비교해 여행 MBTI 코드를 결정한다. 동점이면
 * {@link OnboardingAxis#tieBreakLetter()}(I/N/F/P)를 채택하고, 최종 코드는
 * {@link OnboardingQuestionBank#AXIS_ORDER}(EI+SN+TF+JP) 순서로 합성한다.
 */
public final class MbtiScorer {

    private MbtiScorer() {
    }

    /** @param choiceByQuestionNumber 1~12번 문항 각각의 선택(1 또는 2). 정확히 12개여야 한다. */
    public static String score(Map<Integer, Integer> choiceByQuestionNumber) {
        StringBuilder code = new StringBuilder(4);
        for (OnboardingAxis axis : OnboardingQuestionBank.AXIS_ORDER) {
            code.append(scoreAxis(axis, choiceByQuestionNumber));
        }
        return code.toString();
    }

    private static char scoreAxis(OnboardingAxis axis, Map<Integer, Integer> choiceByQuestionNumber) {
        int firstCount = 0;
        int secondCount = 0;
        for (OnboardingQuestion question : OnboardingQuestionBank.QUESTIONS) {
            if (question.axis() != axis) {
                continue;
            }
            char letter = question.letterFor(choiceByQuestionNumber.get(question.number()));
            if (letter == axis.first()) {
                firstCount++;
            } else {
                secondCount++;
            }
        }
        if (firstCount == secondCount) {
            return axis.tieBreakLetter();
        }
        return firstCount > secondCount ? axis.first() : axis.second();
    }
}
