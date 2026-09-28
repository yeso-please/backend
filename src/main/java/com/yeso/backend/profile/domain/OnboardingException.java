package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

/** onboarding 도메인 베이스 예외(docs/conventions/코드.md). */
public abstract class OnboardingException extends DomainException {

    protected OnboardingException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
