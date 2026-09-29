package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class TooManyLikedRegionsException extends OnboardingException {
    public TooManyLikedRegionsException() {
        this(OnboardingQuestionBank.MAX_LIKED_TRIPS, "좋았던 여행지");
    }

    public TooManyLikedRegionsException(int maximum, String label) {
        super(ErrorCode.TOO_MANY_LIKED_REGIONS,
                label + "은(는) 최대 " + maximum + "개까지 선택할 수 있습니다.");
    }
}
