package com.yeso.backend.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void domainException_includesItsStableCodeAndRequestPath() {
        MockHttpServletRequest request = request("/api/example");

        var response = handler.handleDomainException(
                new TestDomainException(ErrorCode.USER_NOT_FOUND, "사용자를 찾을 수 없습니다."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .extracting(ApiErrorResponse::code, ApiErrorResponse::path)
                .containsExactly("AUTH_USER_NOT_FOUND", "/api/example");
    }

    @Test
    void validationException_includesFieldErrors() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "email", "이메일 형식이 올바르지 않습니다."));

        var response = handler.handleValidation(new BindException(result), request("/api/auth/signup"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .extracting(ApiErrorResponse::code, ApiErrorResponse::path)
                .containsExactly("COMMON_INVALID_REQUEST", "/api/auth/signup");
        assertThat(response.getBody().fieldErrors())
                .containsExactly(new ApiErrorResponse.FieldError("email", "이메일 형식이 올바르지 않습니다."));
    }

    @Test
    void methodNotAllowed_usesDedicatedCommonCode() {
        var response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"), request("/api/users/me"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo("COMMON_METHOD_NOT_ALLOWED");
    }

    @Test
    void unexpectedException_hidesInternalMessage() {
        var response = handler.handleUnexpected(
                new IllegalStateException("database password leaked"), request("/api/example"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .extracting(ApiErrorResponse::code, ApiErrorResponse::message)
                .containsExactly("COMMON_INTERNAL_ERROR", "서버 오류가 발생했습니다.");
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static class TestDomainException extends DomainException {
        private TestDomainException(ErrorCode errorCode, String message) {
            super(errorCode, message);
        }
    }
}
