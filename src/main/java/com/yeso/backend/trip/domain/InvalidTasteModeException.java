package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 코스 생성(5-1)의 {@code tasteMode}가 {@code TASTE|RANDOM}이 아님. 요청 형식 위반으로 400을 준다. */
public class InvalidTasteModeException extends TripException {
    public InvalidTasteModeException(String value) {
        super(ErrorCode.INVALID_REQUEST, "tasteMode는 TASTE 또는 RANDOM이어야 합니다: " + value);
    }
}
