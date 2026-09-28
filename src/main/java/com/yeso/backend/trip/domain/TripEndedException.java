package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 종료일이 지난 여행은 읽기 전용이다. 조회·공유·탈퇴만 된다. */
public class TripEndedException extends TripException {
    public TripEndedException(Long tripId) {
        super(ErrorCode.TRIP_ENDED, "종료된 여행은 바꿀 수 없습니다: " + tripId);
    }
}
