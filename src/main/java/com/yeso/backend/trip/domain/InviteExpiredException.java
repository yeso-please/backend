package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InviteExpiredException extends InviteException {
    public InviteExpiredException() {
        super(ErrorCode.INVITE_EXPIRED, "만료된 초대 링크입니다.");
    }
}
