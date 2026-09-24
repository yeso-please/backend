package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class TripContextLockedException extends TripException {
    public TripContextLockedException() {
        super(ErrorCode.TRIP_CONTEXT_LOCKED, "확정되었거나 취소된 여행은 context를 수정할 수 없습니다.");
    }
}
