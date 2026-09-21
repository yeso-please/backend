package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** origin은 lat/lng 둘 다 있거나 둘 다 없어야 하고, 있으면 유효 범위여야 한다. */
public class InvalidOriginException extends TripException {
    public InvalidOriginException() {
        super(ErrorCode.INVALID_ORIGIN, "origin은 lat/lng를 함께 주거나 함께 생략해야 합니다.");
    }
}
