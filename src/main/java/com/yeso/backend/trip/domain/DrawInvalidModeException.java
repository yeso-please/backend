package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** {@code mode} 값이 {@code RANDOM|CONDITIONAL|MANUAL}이 아님(3-7). */
public class DrawInvalidModeException extends TripException {
    public DrawInvalidModeException() {
        super(ErrorCode.DRAW_INVALID_MODE, "mode는 RANDOM, CONDITIONAL, MANUAL 중 하나여야 합니다.");
    }
}
