package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 없거나, 나에게 온 초대가 아니거나, 이 여행의 초대가 아닌 친구 초대. 존재를 구분하지 않는다. */
public class FriendInvitationNotFoundException extends InviteException {
    public FriendInvitationNotFoundException() {
        super(ErrorCode.INVITE_NOT_FOUND, "초대를 찾을 수 없습니다.");
    }
}
