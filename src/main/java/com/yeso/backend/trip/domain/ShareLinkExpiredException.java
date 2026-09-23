package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class ShareLinkExpiredException extends InviteException {
    public ShareLinkExpiredException() {
        super(ErrorCode.SHARE_LINK_EXPIRED, "만료된 공유 링크입니다.");
    }
}
