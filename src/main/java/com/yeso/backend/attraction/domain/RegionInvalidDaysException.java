package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 여행 일수가 1~7 밖. */
public class RegionInvalidDaysException extends AttractionException {
    public RegionInvalidDaysException(int days) {
        super(ErrorCode.REGION_INVALID_DAYS, "여행 일수는 1~7일이어야 합니다: " + days);
    }
}
