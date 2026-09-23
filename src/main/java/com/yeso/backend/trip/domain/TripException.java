package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

import java.util.Map;

/** trip 도메인 베이스 예외(docs/conventions/예외-처리.md). */
public abstract class TripException extends DomainException {

    protected TripException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    protected TripException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details);
    }
}
