package com.yeso.backend.onboarding.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidScheduleDensityException extends OnboardingException {
    public InvalidScheduleDensityException() {
        super(ErrorCode.INVALID_SCHEDULE_DENSITY, "scheduleDensity는 RELAXED 또는 PACKED여야 합니다.");
    }
}
