package com.yeso.backend.shared.token;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

public class InvalidExpiresInDaysException extends DomainException {
    public InvalidExpiresInDaysException() {
        super(ErrorCode.INVALID_EXPIRES_IN_DAYS, "expiresInDays는 1~30 사이여야 합니다.");
    }
}
