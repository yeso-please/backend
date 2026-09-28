package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidNightsException extends TripException {
    public InvalidNightsException() {
        super(ErrorCode.INVALID_NIGHTS, "nights는 0~6 사이여야 합니다.");
    }
}
