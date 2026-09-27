package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 코스 생성 요청의 선택값(밀도·취향 반영 방식)이 허용 값이 아니다(COMMON_INVALID_REQUEST). */
public class InvalidCourseOptionException extends TripException {
    public InvalidCourseOptionException(String field, String value) {
        super(ErrorCode.INVALID_REQUEST, field + " 값이 올바르지 않습니다: " + value);
    }
}
