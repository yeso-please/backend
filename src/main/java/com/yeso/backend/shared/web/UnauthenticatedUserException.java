package com.yeso.backend.shared.web;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

public class UnauthenticatedUserException extends DomainException {
    public UnauthenticatedUserException() {
        super(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
}
