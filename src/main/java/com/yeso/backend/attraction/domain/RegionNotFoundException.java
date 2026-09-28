package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 없는 시군구 코드. */
public class RegionNotFoundException extends AttractionException {
    public RegionNotFoundException(String sigCd) {
        super(ErrorCode.REGION_NOT_FOUND, "지역을 찾을 수 없습니다: " + sigCd);
    }
}
