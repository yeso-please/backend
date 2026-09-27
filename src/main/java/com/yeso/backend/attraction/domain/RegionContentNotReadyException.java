package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 승인된 지역 소개문이나 검증된 대표 이미지가 없다. */
public class RegionContentNotReadyException extends AttractionException {
    public RegionContentNotReadyException(String sigCd) {
        super(ErrorCode.REGION_CONTENT_NOT_READY, "지역 소개가 아직 준비되지 않았습니다: " + sigCd);
    }
}
