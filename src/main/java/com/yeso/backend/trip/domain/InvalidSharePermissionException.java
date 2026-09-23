package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidSharePermissionException extends InviteException {
    public InvalidSharePermissionException() {
        super(ErrorCode.INVALID_SHARE_PERMISSION, "permission은 VIEW 또는 EDIT여야 합니다.");
    }
}
