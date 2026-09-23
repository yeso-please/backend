package com.yeso.backend.onboarding.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class TooManyLikedRegionsException extends OnboardingException {
    public TooManyLikedRegionsException() {
        super(ErrorCode.TOO_MANY_LIKED_REGIONS,
                "좋았던 여행지는 최대 " + OnboardingQuestionBank.MAX_LIKED_TRIPS + "개까지 담을 수 있습니다.");
    }
}
