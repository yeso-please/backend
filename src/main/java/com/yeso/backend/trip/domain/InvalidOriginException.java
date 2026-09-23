package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** origin은 lat/lng 둘 다 있거나 둘 다 없어야 하고, 있으면 유효 범위여야 한다. */
public class InvalidOriginException extends TripException {
    private InvalidOriginException(String message) {
        super(ErrorCode.INVALID_ORIGIN, message);
    }

    public static InvalidOriginException missingPair() {
        return new InvalidOriginException("origin은 lat/lng를 함께 주거나 함께 생략해야 합니다.");
    }

    public static InvalidOriginException outOfRange() {
        return new InvalidOriginException("origin의 lat은 -90~90, lng는 -180~180 범위여야 합니다.");
    }
}
