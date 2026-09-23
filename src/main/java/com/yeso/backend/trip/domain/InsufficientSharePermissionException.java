package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** VIEW 공유 세션으로 편집을 시도했을 때. */
public class InsufficientSharePermissionException extends InviteException {

    public InsufficientSharePermissionException() {
        super(ErrorCode.INSUFFICIENT_SHARE_PERMISSION, "이 공유 링크에는 편집 권한이 없습니다.");
    }
}
