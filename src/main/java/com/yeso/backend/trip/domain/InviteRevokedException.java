package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InviteRevokedException extends InviteException {
    public InviteRevokedException() {
        super(ErrorCode.INVITE_REVOKED, "폐기된 초대 링크입니다.");
    }
}
