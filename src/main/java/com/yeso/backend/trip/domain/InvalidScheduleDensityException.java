package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** {@code scheduleDensity}가 {@code RELAXED|PACKED}가 아님(3-7). 요청 형식 위반으로 400을 준다. */
public class InvalidScheduleDensityException extends TripException {
    public InvalidScheduleDensityException() {
        super(ErrorCode.INVALID_REQUEST, "scheduleDensity는 RELAXED 또는 PACKED여야 합니다.");
    }
}
