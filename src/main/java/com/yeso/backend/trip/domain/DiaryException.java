package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.DomainException;
import com.yeso.backend.shared.exception.ErrorCode;

import java.util.Map;

public class DiaryException extends DomainException {

    public DiaryException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public DiaryException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details);
    }
}
