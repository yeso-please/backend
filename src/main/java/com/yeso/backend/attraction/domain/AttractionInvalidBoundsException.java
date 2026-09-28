package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 지도 영역(bbox) 형식 오류, 최소가 최대보다 큼, 한국 범위 밖. */
public class AttractionInvalidBoundsException extends AttractionException {
    public AttractionInvalidBoundsException(String bbox) {
        super(ErrorCode.ATTRACTION_INVALID_BOUNDS, "지도 영역이 올바르지 않습니다: " + bbox);
    }
}
