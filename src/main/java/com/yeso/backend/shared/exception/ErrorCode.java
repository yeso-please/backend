package com.yeso.backend.shared.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_INVALID_REQUEST"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_METHOD_NOT_ALLOWED"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_ACCESS_DENIED"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH_DUPLICATE_EMAIL"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_USER_NOT_FOUND"),

    INVALID_START_DATE(HttpStatus.BAD_REQUEST, "TRIP_INVALID_START_DATE"),
    INVALID_NIGHTS(HttpStatus.BAD_REQUEST, "TRIP_INVALID_NIGHTS"),
    INVALID_TRANSPORT(HttpStatus.BAD_REQUEST, "TRIP_INVALID_TRANSPORT"),
    INVALID_ORIGIN(HttpStatus.BAD_REQUEST, "TRIP_INVALID_ORIGIN"),
    TRIP_DATE_OVERLAP(HttpStatus.CONFLICT, "TRIP_DATE_OVERLAP"),
    TRIP_NOT_FOUND(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND"),
    TRIP_CONTEXT_LOCKED(HttpStatus.CONFLICT, "TRIP_CONTEXT_LOCKED"),
    TRIP_VERSION_CONFLICT(HttpStatus.CONFLICT, "TRIP_VERSION_CONFLICT"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_INTERNAL_ERROR");

    private final HttpStatus status;
    private final String code;

    ErrorCode(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
