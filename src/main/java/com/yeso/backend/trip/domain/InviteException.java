package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

/** invite/share 도메인 베이스 예외(docs/conventions/코드.md). */
public abstract class InviteException extends DomainException {

    protected InviteException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
