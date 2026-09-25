package com.yeso.backend.shared.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(
            DomainException exception, HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        log.warn("Domain exception code={}: {}", errorCode.code(), exception.getMessage());
        return ResponseEntity.status(errorCode.status())
                .body(ApiErrorResponse.of(
                        errorCode, exception.getMessage(), request.getRequestURI(), exception.getDetails()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiErrorResponse> handleValidation(BindException exception, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiErrorResponse.FieldError(
                        fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        String message = fieldErrors.stream()
                .map(fieldError -> fieldError.field() + ": " + fieldError.message())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validation(message, request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestValueException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        log.warn("Invalid request: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(ErrorCode.INVALID_REQUEST, "요청 형식이 올바르지 않습니다.", request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request
    ) {
        log.warn("Method not supported: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrorResponse.of(
                        ErrorCode.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다.", request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        log.warn("No resource: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(ErrorCode.NOT_FOUND, "존재하지 않는 API 경로입니다.", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected exception", exception);
        return ResponseEntity.internalServerError()
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.", request.getRequestURI()));
    }
}
