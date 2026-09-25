package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 없는 친구 초대 링크이거나 내 링크가 아닐 때. */
public class FriendLinkNotFoundException extends FriendException {
    public FriendLinkNotFoundException() {
        super(ErrorCode.FRIEND_LINK_NOT_FOUND, "친구 초대 링크를 찾을 수 없습니다.");
    }
}
