package com.yeso.backend.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 각 테스트는 운영과 같은 PostgreSQL 스키마를 Testcontainers로 구성하고
 * @Transactional로 자동 롤백한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "jwt.secret=test-only-secret-not-used-outside-automated-tests",
        "spring.jpa.properties.hibernate.default_schema=app"
})
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("tripin_auth_test")
            .withUsername("tripin_test")
            .withPassword("tripin_test");

    @Autowired
    private MockMvc mockMvc;

    private static String signupBody(String email, String password, String nickname) {
        return """
                {"email":"%s","password":"%s","nickname":"%s"}
                """.formatted(email, password, nickname);
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("유효한 요청이면 201과 함께 토큰을 발급한다")
        void signup_success() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("signup1@example.com", "password123", "tester")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"));
        }

        @Test
        @DisplayName("이메일이 중복이면 409를 반환한다")
        void signup_duplicateEmail() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody("dup@example.com", "password123", "first")));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("dup@example.com", "password123", "second")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("비밀번호가 8자 미만이면 400을 반환한다")
        void signup_passwordTooShort() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("shortpw@example.com", "short", "tester")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("이메일 형식이 올바르지 않으면 400을 반환한다")
        void signup_invalidEmail() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("not-an-email", "password123", "tester")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("올바른 이메일/비밀번호면 200과 함께 토큰을 발급한다")
        void login_success() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody("login1@example.com", "password123", "tester")));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"login1@example.com","password":"password123"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
        }

        @Test
        @DisplayName("비밀번호가 틀리면 이메일 미존재와 동일한 401 메시지를 반환한다")
        void login_wrongPasswordAndUnknownEmail_returnSameMessage() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody("login2@example.com", "password123", "tester")));

            MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"login2@example.com","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andReturn();

            MvcResult unknownEmail = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"nobody@example.com","password":"password123"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andReturn();

            String wrongPasswordMessage = JsonPath.read(wrongPassword.getResponse().getContentAsString(), "$.message");
            String unknownEmailMessage = JsonPath.read(unknownEmail.getResponse().getContentAsString(), "$.message");
            assertThat(wrongPasswordMessage).isEqualTo(unknownEmailMessage);
        }
    }

    @Nested
    @DisplayName("내 정보 조회")
    class Me {

        @Test
        @DisplayName("유효한 access 토큰이면 200과 내 정보를 반환한다")
        void me_success() throws Exception {
            MvcResult signup = mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("me1@example.com", "password123", "tester")))
                    .andReturn();
            String accessToken = JsonPath.read(signup.getResponse().getContentAsString(), "$.accessToken");

            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("me1@example.com"))
                    .andExpect(jsonPath("$.nickname").value("tester"));
        }

        @Test
        @DisplayName("토큰이 없으면 401을 반환한다")
        void me_withoutToken() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("위조된 토큰이면 401을 반환한다")
        void me_withTamperedToken() throws Exception {
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-jwt"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("토큰 재발급")
    class Refresh {

        @Test
        @DisplayName("유효한 refresh 토큰이면 새 access/refresh 토큰을 발급한다")
        void refresh_success() throws Exception {
            MvcResult signup = mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("refresh1@example.com", "password123", "tester")))
                    .andReturn();
            String refreshToken = JsonPath.read(signup.getResponse().getContentAsString(), "$.refreshToken");

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}
                                    """.formatted(refreshToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.not(refreshToken)));
        }

        @Test
        @DisplayName("이미 회전(rotate)된 refresh 토큰을 재사용하면 401을 반환한다")
        void refresh_reusedRotatedToken() throws Exception {
            MvcResult signup = mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("refresh2@example.com", "password123", "tester")))
                    .andReturn();
            String originalRefreshToken = JsonPath.read(signup.getResponse().getContentAsString(), "$.refreshToken");

            mockMvc.perform(post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"refreshToken":"%s"}
                            """.formatted(originalRefreshToken)));

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}
                                    """.formatted(originalRefreshToken)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("형식이 올바르지 않은 토큰이면 401을 반환한다")
        void refresh_malformedToken() throws Exception {
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"not-a-real-jwt"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("로그아웃 후 같은 refresh 토큰으로 재발급을 시도하면 401을 반환한다")
        void logout_thenRefresh_returnsUnauthorized() throws Exception {
            MvcResult signup = mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("logout1@example.com", "password123", "tester")))
                    .andReturn();
            String refreshToken = JsonPath.read(signup.getResponse().getContentAsString(), "$.refreshToken");

            mockMvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}
                                    """.formatted(refreshToken)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}
                                    """.formatted(refreshToken)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
