package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class CourseInvalidQueryException extends TripException {
    public CourseInvalidQueryException() {
        this("대체 후보 검색 조건이 올바르지 않습니다.");
    }

    public CourseInvalidQueryException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
