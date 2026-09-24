package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class ShareSessionInvalidException extends InviteException {
    public ShareSessionInvalidException() {
        super(ErrorCode.SHARE_SESSION_INVALID, "공유 세션이 없거나 유효하지 않습니다. 링크를 다시 열어주세요.");
    }
}
