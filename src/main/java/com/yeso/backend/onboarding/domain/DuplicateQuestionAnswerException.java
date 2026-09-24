package com.yeso.backend.onboarding.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class DuplicateQuestionAnswerException extends OnboardingException {
    public DuplicateQuestionAnswerException(int questionNumber) {
        super(ErrorCode.DUPLICATE_QUESTION_ANSWER, questionNumber + "번 문항에 답이 중복으로 제출됐습니다.");
    }
}
