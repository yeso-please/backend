package com.yeso.backend.shared.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller 파라미터에 붙여 인증된 사용자(CustomUserDetails)를 주입받는다.
 * Service는 SecurityContextHolder를 직접 호출하지 않고 Controller가 userId만 넘긴다
 * (docs/conventions/코드.md).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
