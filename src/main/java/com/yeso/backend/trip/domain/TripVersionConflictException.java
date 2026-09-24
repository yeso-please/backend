package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class TripVersionConflictException extends TripException {
    public TripVersionConflictException() {
        super(ErrorCode.TRIP_VERSION_CONFLICT, "다른 요청이 먼저 이 여행을 변경했습니다. 최신 버전을 다시 조회해주세요.");
    }
}
