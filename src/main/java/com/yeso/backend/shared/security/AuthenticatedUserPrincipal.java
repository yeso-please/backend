package com.yeso.backend.shared.security;

/** JWT principal이 구현할 최소 계약이다. */
public interface AuthenticatedUserPrincipal {
    Long userId();
}
