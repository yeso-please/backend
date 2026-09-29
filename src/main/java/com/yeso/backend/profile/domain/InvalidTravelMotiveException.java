package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidTravelMotiveException extends OnboardingException {
    public InvalidTravelMotiveException() {
        super(ErrorCode.INVALID_TRAVEL_MOTIVE,
                "여행 동기는 코드 1~9 중 중복 없이 최대 3개까지 선택할 수 있습니다.");
    }
}
