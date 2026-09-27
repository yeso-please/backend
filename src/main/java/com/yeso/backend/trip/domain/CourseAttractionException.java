package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 코스에 새로 넣을 관광지의 지역·추천 가능 여부·중복 규칙. */
public class CourseAttractionException extends TripException {
    public CourseAttractionException(ErrorCode code, Long attractionId) {
        super(code, "코스에 넣을 수 없는 관광지입니다: " + attractionId);
    }
}
