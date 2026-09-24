package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class TripContextLockedException extends TripException {
    public TripContextLockedException() {
        super(ErrorCode.TRIP_CONTEXT_LOCKED, "코스가 만들어진 여행은 이동수단·출발지를 바꿀 수 없습니다.");
    }
}
