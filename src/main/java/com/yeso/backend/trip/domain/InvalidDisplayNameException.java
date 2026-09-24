package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidDisplayNameException extends InviteException {
    public InvalidDisplayNameException() {
        super(ErrorCode.INVALID_DISPLAY_NAME, "표시 이름은 1~30자여야 합니다.");
    }
}
