package com.yeso.backend.shared.security;

import com.jayway.jsonpath.JsonPath;
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
