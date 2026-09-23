package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidExpiresInDaysException extends InviteException {
    public InvalidExpiresInDaysException() {
        super(ErrorCode.INVALID_EXPIRES_IN_DAYS, "expiresInDays는 1~30 사이여야 합니다.");
    }
}
