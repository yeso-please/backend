package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

/** attraction 도메인 베이스 예외(docs/conventions/코드.md). */
public abstract class AttractionException extends DomainException {

    protected AttractionException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
