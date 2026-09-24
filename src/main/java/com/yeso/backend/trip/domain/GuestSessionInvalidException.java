package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class GuestSessionInvalidException extends InviteException {
    public GuestSessionInvalidException() {
        super(ErrorCode.GUEST_SESSION_INVALID, "guest session이 없거나 유효하지 않습니다.");
    }
}
