package com.yeso.backend.shared.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityErrorHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("인증이 없으면 401 AUTH_UNAUTHENTICATED와 요청 path를 쓴다")
    void authenticationEntryPoint_returnsCommonUnauthorizedResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiAuthenticationEntryPoint(objectMapper).commence(
                request, response, new InsufficientAuthenticationException("missing authentication"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
                .isEqualTo("AUTH_UNAUTHENTICATED");
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.path")).isEqualTo("/api/users/me");
    }

    @Test
    @DisplayName("권한이 없으면 403 AUTH_ACCESS_DENIED와 요청 path를 쓴다")
    void accessDeniedHandler_returnsCommonForbiddenResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiAccessDeniedHandler(objectMapper).handle(
                request, response, new AccessDeniedException("forbidden"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
                .isEqualTo("AUTH_ACCESS_DENIED");
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.path")).isEqualTo("/api/admin");
    }
}
