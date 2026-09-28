package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 폐기된 친구 초대 링크. */
public class FriendLinkRevokedException extends FriendException {
    public FriendLinkRevokedException() {
        super(ErrorCode.FRIEND_LINK_REVOKED, "폐기된 친구 초대 링크입니다.");
    }
}
