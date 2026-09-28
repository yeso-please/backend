package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 지역·관광지 조회 파라미터(limit·cursor·밀도)가 올바르지 않다. 명세상 COMMON_INVALID_REQUEST다. */
public class InvalidAttractionQueryException extends AttractionException {
    public InvalidAttractionQueryException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
