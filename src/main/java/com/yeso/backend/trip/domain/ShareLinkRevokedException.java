package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class ShareLinkRevokedException extends InviteException {
    public ShareLinkRevokedException() {
        super(ErrorCode.SHARE_LINK_REVOKED, "폐기된 공유 링크입니다.");
    }
}
