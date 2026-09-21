package com.yeso.backend.shared.security;

/** WORK-01의 JWT principal이 구현할 최소 계약이다. */
public interface AuthenticatedUserPrincipal {
    Long userId();
}
