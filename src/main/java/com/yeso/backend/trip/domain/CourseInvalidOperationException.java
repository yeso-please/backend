package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 편집 operation 형식 오류, 또는 {@code itemId}의 항목 타입이 그 API·operation에 맞지 않을 때(docs/api/trip.md 5장). */
public class CourseInvalidOperationException extends TripException {
    public CourseInvalidOperationException(String message) {
        super(ErrorCode.COURSE_INVALID_OPERATION, message);
    }
}
