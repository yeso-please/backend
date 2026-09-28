package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 여행은 있으나 코스를 아직 만들지 않았거나 3-7 {@code replaceCourse}로 비워졌을 때(docs/api/trip.md 5장). */
public class CourseNotFoundException extends TripException {
    public CourseNotFoundException(Long tripId) {
        super(ErrorCode.COURSE_NOT_FOUND, "코스가 없습니다: " + tripId);
    }
}
