package com.yeso.backend.profile.domain;

/**
 * 질문 1개의 고정 데이터. choice1/choice2의 글자 매핑은 질문마다 다를 수 있어
 * (4번 문항은 1↔2가 다른 문항과 반대) 축의 first/second로 유추하지 않고 각각 명시한다.
 */
public record OnboardingQuestion(
        int number,
        OnboardingAxis axis,
        String prompt,
        String choice1Text,
        char choice1Letter,
        String choice2Text,
        char choice2Letter
) {
    public char letterFor(int choice) {
        if (choice == 1) {
            return choice1Letter;
        }
        if (choice == 2) {
            return choice2Letter;
        }
        throw new IllegalArgumentException("choice는 1 또는 2여야 합니다: " + choice);
    }
}
