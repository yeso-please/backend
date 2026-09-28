package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 만료된 친구 초대 링크. */
public class FriendLinkExpiredException extends FriendException {
    public FriendLinkExpiredException() {
        super(ErrorCode.FRIEND_LINK_EXPIRED, "만료된 친구 초대 링크입니다.");
    }
}
