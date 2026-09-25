package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 코스에 없는 {@code itemId}(docs/api/trip.md 5장). */
public class CourseItemNotFoundException extends TripException {
    public CourseItemNotFoundException(String itemId) {
        super(ErrorCode.COURSE_ITEM_NOT_FOUND, "코스 항목이 없습니다: " + itemId);
    }
}
