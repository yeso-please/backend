package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 코스가 없을 때(첫 생성) 여행을 만든 사람이 아닌 참여자가 만들려 한다. 만든 사람이 탈퇴했으면 누구나 만들 수 있다. */
public class CourseCreatorOnlyException extends TripException {
    public CourseCreatorOnlyException(Long tripId) {
        super(ErrorCode.COURSE_CREATOR_ONLY, "첫 코스는 여행을 만든 사람만 만들 수 있습니다: " + tripId);
    }
}
