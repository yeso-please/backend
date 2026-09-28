package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class ShareLinkNotFoundException extends InviteException {
    public ShareLinkNotFoundException() {
        super(ErrorCode.SHARE_LINK_NOT_FOUND, "공유 링크를 찾을 수 없습니다.");
    }
}
