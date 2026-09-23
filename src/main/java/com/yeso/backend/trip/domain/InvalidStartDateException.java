package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidStartDateException extends TripException {
    public InvalidStartDateException() {
        super(ErrorCode.INVALID_START_DATE, "startDate는 오늘 이후여야 합니다.");
    }
}
