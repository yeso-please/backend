package com.yeso.backend.auth.domain;

import org.springframework.http.HttpStatus;

/**
 * 이메일 미존재와 비밀번호 불일치를 구분하지 않고 같은 메시지로 응답한다
 * (계정 존재 여부 노출 방지, docs/FEATURE-SPEC.md §1.2).
 */
public class InvalidCredentialsException extends AuthException {
    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
