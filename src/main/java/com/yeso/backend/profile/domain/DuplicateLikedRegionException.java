package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class DuplicateLikedRegionException extends OnboardingException {
    public DuplicateLikedRegionException(String sigCd) {
        super(ErrorCode.DUPLICATE_LIKED_REGION, "같은 지역은 한 번만 담을 수 있습니다: " + sigCd);
    }
}
