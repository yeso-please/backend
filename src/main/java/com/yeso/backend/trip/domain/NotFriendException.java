package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 친구가 아닌 회원을 여행에 초대할 때(docs/api/trip.md 4-6). 친구 관계는 보낼 때만 확인한다. */
public class NotFriendException extends InviteException {
    public NotFriendException(Long userId) {
        super(ErrorCode.FRIEND_NOT_FOUND, "친구가 아닌 사용자입니다: " + userId);
    }
}
