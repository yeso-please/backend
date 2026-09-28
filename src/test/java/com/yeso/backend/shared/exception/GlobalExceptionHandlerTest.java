package com.yeso.backend.shared.exception;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("도메인 예외면 그 예외의 고정 code와 요청 path를 담는다")
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
    @DisplayName("재시도 시간이 있는 도메인 예외면 Retry-After 헤더(초)를 싣는다")
    void domainException_withRetryAfter_setsRetryAfterHeader() {
        DomainException exception = new TestDomainException(ErrorCode.COURSE_KAKAO_LOCAL_RATE_LIMITED, "호출 한도") {
            @Override
            public java.util.Optional<java.time.Duration> getRetryAfter() {
                return java.util.Optional.of(java.time.Duration.ofSeconds(60));
            }
        };

        var response = handler.handleDomainException(exception, request("/api/courses/1/restaurants/search"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("재시도 시간이 없으면 Retry-After 헤더가 없다")
    void domainException_withoutRetryAfter_hasNoRetryAfterHeader() {
        var response = handler.handleDomainException(
                new TestDomainException(ErrorCode.USER_NOT_FOUND, "없음"), request("/api/example"));

        assertThat(response.getHeaders().containsHeader("Retry-After")).isFalse();
    }

    @Test
    @DisplayName("검증 실패면 400 COMMON_INVALID_REQUEST와 fieldErrors를 담는다")
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
    @DisplayName("지원하지 않는 메서드면 405 COMMON_METHOD_NOT_ALLOWED다")
    void methodNotAllowed_usesDedicatedCommonCode() {
        var response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"), request("/api/users/me"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo("COMMON_METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("예상 못 한 예외면 500 COMMON_INTERNAL_ERROR이고 내부 메시지를 숨긴다")
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
