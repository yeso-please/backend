package com.yeso.backend.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors,
        Map<String, Object> details
) {
    public static ApiErrorResponse of(ErrorCode errorCode, String message, String path) {
        return new ApiErrorResponse(
                Instant.now(), errorCode.status().value(), errorCode.code(), message, path, List.of(), Map.of());
    }

    public static ApiErrorResponse of(ErrorCode errorCode, String message, String path, Map<String, Object> details) {
        return new ApiErrorResponse(
                Instant.now(), errorCode.status().value(), errorCode.code(), message, path, List.of(), details);
    }

    public static ApiErrorResponse validation(String message, String path, List<FieldError> fieldErrors) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return new ApiErrorResponse(
                Instant.now(), errorCode.status().value(), errorCode.code(), message, path, fieldErrors, Map.of());
    }

    public record FieldError(String field, String message) {
    }
}
