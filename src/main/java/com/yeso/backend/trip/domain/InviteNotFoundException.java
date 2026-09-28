package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InviteNotFoundException extends InviteException {
    public InviteNotFoundException() {
        super(ErrorCode.INVITE_NOT_FOUND, "초대 링크를 찾을 수 없습니다.");
    }
}
