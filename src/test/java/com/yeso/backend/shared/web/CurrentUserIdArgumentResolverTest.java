package com.yeso.backend.shared.web;

import com.yeso.backend.shared.security.AuthenticatedUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserIdArgumentResolverTest {

    private final CurrentUserIdArgumentResolver resolver = new CurrentUserIdArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("인증 principal에서 사용자 ID를 주입한다")
    void resolveArgument_authenticatedPrincipal_returnsUserId() throws Exception {
        AuthenticatedUserPrincipal principal = () -> 42L;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        Object resolved = resolver.resolveArgument(parameter(), null, null, null);

        assertThat(resolved).isEqualTo(42L);
    }

    @Test
    @DisplayName("인증 사용자가 없으면 공통 인증 예외를 던진다")
    void resolveArgument_missingPrincipal_throwsUnauthenticated() throws Exception {
        assertThatThrownBy(() -> resolver.resolveArgument(parameter(), null, null, null))
                .isInstanceOf(UnauthenticatedUserException.class);
    }

    private MethodParameter parameter() throws Exception {
        Method method = Fixture.class.getDeclaredMethod("endpoint", Long.class);
        return new MethodParameter(method, 0);
    }

    private static class Fixture {
        void endpoint(@CurrentUserId Long userId) {
        }
    }
}
