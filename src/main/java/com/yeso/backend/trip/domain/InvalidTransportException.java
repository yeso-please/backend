package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidTransportException extends TripException {
    public InvalidTransportException() {
        super(ErrorCode.INVALID_TRANSPORT, "transport는 WALK, CAR, PUBLIC_TRANSIT 중 하나여야 합니다.");
    }
}
