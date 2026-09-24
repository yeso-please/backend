package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class MissingQuestionAnswerException extends OnboardingException {
    public MissingQuestionAnswerException(int questionNumber) {
        super(ErrorCode.MISSING_QUESTION_ANSWER, questionNumber + "번 문항에 대한 답이 없습니다.");
    }
}
