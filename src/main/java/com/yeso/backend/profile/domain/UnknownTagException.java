package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class UnknownTagException extends OnboardingException {
    public UnknownTagException(String tag) {
        super(ErrorCode.UNKNOWN_TAG, "알 수 없는 태그입니다: " + tag);
    }
}
