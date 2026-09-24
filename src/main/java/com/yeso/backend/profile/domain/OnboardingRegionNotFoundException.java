package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 알려지지 않은 지역 코드는 400이다(docs/mvp/onboarding-questionnaire.md) — 404가 아니다. */
public class OnboardingRegionNotFoundException extends OnboardingException {
    public OnboardingRegionNotFoundException(String sigCd) {
        super(ErrorCode.REGION_NOT_FOUND, "존재하지 않는 지역 코드입니다: " + sigCd);
    }
}
