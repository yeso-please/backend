package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 여행을 만들거나 초대를 수락하려면 최초 설문을 먼저 마쳐야 한다. */
public class OnboardingRequiredException extends TripException {
    public OnboardingRequiredException() {
        super(ErrorCode.ONBOARDING_REQUIRED, "여행 성향 설문을 먼저 완료해주세요.");
    }
}
