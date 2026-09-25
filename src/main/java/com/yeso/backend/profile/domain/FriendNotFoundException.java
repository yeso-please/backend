package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 친구가 아닌 사용자. */
public class FriendNotFoundException extends FriendException {
    public FriendNotFoundException(Long userId) {
        super(ErrorCode.FRIEND_NOT_FOUND, "친구가 아닌 사용자입니다: " + userId);
    }
}
