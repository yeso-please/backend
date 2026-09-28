package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidChoiceException extends OnboardingException {
    public InvalidChoiceException(int questionNumber) {
        super(ErrorCode.INVALID_CHOICE, questionNumber + "번 문항의 choice는 1 또는 2여야 합니다.");
    }
}
