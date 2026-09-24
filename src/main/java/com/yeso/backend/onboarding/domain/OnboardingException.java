package com.yeso.backend.onboarding.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

/** onboarding 도메인 베이스 예외(docs/conventions/예외-처리.md). */
public abstract class OnboardingException extends DomainException {

    protected OnboardingException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
