package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidTravelStylesException extends OnboardingException {
    public InvalidTravelStylesException() {
        super(ErrorCode.INVALID_TRAVEL_STYLES,
                "여행 스타일은 1·3·5·6번 문항을 각각 1~7로 제출해야 합니다.");
    }
}
