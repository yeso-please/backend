package com.yeso.backend.profile.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 내가 만든 친구 초대 링크를 내가 수락할 때. */
public class FriendLinkSelfException extends FriendException {
    public FriendLinkSelfException() {
        super(ErrorCode.FRIEND_LINK_SELF, "내가 만든 링크는 수락할 수 없습니다.");
    }
}
