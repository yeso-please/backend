package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 없거나 이미 되살린 제외 기록(docs/api/trip.md 5-3 RESTORE). */
public class CourseRemovalNotFoundException extends TripException {
    public CourseRemovalNotFoundException(Long removalId) {
        super(ErrorCode.COURSE_REMOVAL_NOT_FOUND, "되살릴 수 있는 제외 기록이 없습니다: " + removalId);
    }
}
