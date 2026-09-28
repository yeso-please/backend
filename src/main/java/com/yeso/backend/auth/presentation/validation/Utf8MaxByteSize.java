package com.yeso.backend.auth.presentation.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** char 길이가 아니라 UTF-8 byte 길이를 검증한다 — BCrypt는 72 byte를 넘으면 뒷부분을 자른다. */
@Documented
@Constraint(validatedBy = Utf8MaxByteSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8MaxByteSize {

    int max();

    String message() default "허용된 바이트 수를 초과했습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
