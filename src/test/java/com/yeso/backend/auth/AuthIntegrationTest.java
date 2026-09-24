package com.yeso.backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.infrastructure.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 각 테스트는 운영과 같은 PostgreSQL 스키마를 Testcontainers로 구성하고
 * @Transactional로 자동 롤백한다. refresh 토큰은 body가 아니라 HttpOnly cookie로 오간다.
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

    @Autowired
    private UserRepository userRepository;

    private static String signupBody(String email, String password, String nickname) {
        return """
                {"email":"%s","password":"%s","nickname":"%s"}
                """.formatted(email, password, nickname);
    }

    private MvcResult signup(String email, String password, String nickname) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(email, password, nickname)))
                .andReturn();
    }

    private static Cookie refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).as("refresh_token cookie").isNotNull();
        return cookie;
    }

    @Nested
    @DisplayName("OpenAPI 문서")
    class OpenApiDocs {

        @Test
        @DisplayName("익명 사용자가 OpenAPI JSON을 조회할 수 있다")
        void apiDocs_public() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.info.title").value("TriPin Backend API"))
                    .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
        }
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("유효한 요청이면 201과 함께 access 토큰과 refresh cookie를 발급한다")
        void signup_success() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("signup1@example.com", "password123", "tester")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.onboardingCompleted").value(false))
                    .andExpect(jsonPath("$.user.email").value("signup1@example.com"))
                    .andExpect(cookie().exists("refresh_token"))
                    .andExpect(cookie().httpOnly("refresh_token", true))
                    .andExpect(cookie().path("refresh_token", "/api/auth"))
                    .andExpect(cookie().secure("refresh_token", true))
                    .andExpect(cookie().sameSite("refresh_token", "Lax"));
        }

        @Test
        @DisplayName("이메일이 대소문자·공백만 다르면 중복으로 409를 반환한다")
        void signup_duplicateEmail_afterNormalization() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody("dup@example.com", "password123", "first")));

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("  DUP@Example.com  ", "password123", "second")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("AUTH_DUPLICATE_EMAIL"))
                    .andExpect(jsonPath("$.path").value("/api/auth/signup"));
        }

        @Test
        @DisplayName("비밀번호가 8자 미만이면 400을 반환한다")
        void signup_passwordTooShort() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("shortpw@example.com", "short", "tester")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.path").value("/api/auth/signup"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        }

        @Test
        @DisplayName("비밀번호가 UTF-8 기준 72바이트를 넘으면 400을 반환한다")
        void signup_passwordExceedsUtf8ByteLimit() throws Exception {
            // 한글 한 글자는 UTF-8로 3byte다: 25자 = 75byte > 72이지만 char 길이(25)는 64 이하라 @Size는 통과한다.
            String password = "가".repeat(25);

            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("longbytes@example.com", password, "tester")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        }

        @Test
        @DisplayName("이메일 형식이 올바르지 않으면 400을 반환한다")
        void signup_invalidEmail() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("not-an-email", "password123", "tester")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("닉네임 앞뒤 공백은 제거되어 저장된다")
        void signup_nicknameIsTrimmed() throws Exception {
            MvcResult result = signup("trimnick@example.com", "password123", "  tester  ");

            String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                    .andExpect(jsonPath("$.nickname").value("tester"));
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("올바른 이메일/비밀번호면 200과 함께 토큰을 발급한다")
        void login_success() throws Exception {
            signup("login1@example.com", "password123", "tester");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"login1@example.com","password":"password123"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(cookie().exists("refresh_token"));
        }

        @Test
        @DisplayName("이메일 대소문자가 달라도 로그인된다")
        void login_caseInsensitiveEmail() throws Exception {
            signup("caseuser@example.com", "password123", "tester");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"CaseUser@Example.com","password":"password123"}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("비밀번호가 틀리면 이메일 미존재와 동일한 401 메시지를 반환한다")
        void login_wrongPasswordAndUnknownEmail_returnSameMessage() throws Exception {
            signup("login2@example.com", "password123", "tester");

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
        @DisplayName("유효한 access 토큰이면 200과 내 정보·온보딩 상태를 반환한다")
        void me_success() throws Exception {
            MvcResult signup = signup("me1@example.com", "password123", "tester");
            String accessToken = JsonPath.read(signup.getResponse().getContentAsString(), "$.accessToken");

            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("me1@example.com"))
                    .andExpect(jsonPath("$.nickname").value("tester"))
                    .andExpect(jsonPath("$.onboardingCompleted").value(false));
        }

        @Test
        @DisplayName("토큰이 없으면 401을 반환한다")
        void me_withoutToken() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.path").value("/api/users/me"));
        }

        @Test
        @DisplayName("위조된 토큰이면 401을 반환한다")
        void me_withTamperedToken() throws Exception {
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("refresh 토큰(opaque)을 access 토큰 자리에 넣으면 거부된다")
        void me_withRefreshTokenAsAccessToken() throws Exception {
            MvcResult signup = signup("typeconfuse@example.com", "password123", "tester");
            Cookie refreshCookie = refreshCookieOf(signup);

            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + refreshCookie.getValue()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("토큰 재발급")
    class Refresh {

        @Test
        @DisplayName("유효한 refresh cookie면 새 access 토큰과 새 refresh cookie를 발급한다")
        void refresh_success() throws Exception {
            MvcResult signup = signup("refresh1@example.com", "password123", "tester");
            Cookie refreshCookie = refreshCookieOf(signup);

            mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(cookie().value("refresh_token", not(refreshCookie.getValue())));
        }

        @Test
        @DisplayName("이미 회전(rotate)된 refresh 토큰을 재사용하면 401을 반환하고 family 전체가 무효화된다")
        void refresh_reusedRotatedToken_revokesWholeFamily() throws Exception {
            MvcResult signup = signup("refresh2@example.com", "password123", "tester");
            Cookie originalRefreshCookie = refreshCookieOf(signup);

            MvcResult firstRefresh = mockMvc.perform(post("/api/auth/refresh").cookie(originalRefreshCookie))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie rotatedCookie = refreshCookieOf(firstRefresh);

            // 이미 폐기된 최초 토큰 재사용 -> 401 + 이 family(방금 rotate된 토큰 포함)를 통째로 무효화
            mockMvc.perform(post("/api/auth/refresh").cookie(originalRefreshCookie))
                    .andExpect(status().isUnauthorized());

            // family가 전부 폐기됐으므로 정상적으로 rotate된 최신 토큰도 더 이상 쓸 수 없다
            mockMvc.perform(post("/api/auth/refresh").cookie(rotatedCookie))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("형식이 올바르지 않은 토큰이면 401을 반환한다")
        void refresh_malformedToken() throws Exception {
            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new MockCookie("refresh_token", "not-a-real-token")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("cookie가 없으면 401을 반환한다")
        void refresh_withoutCookie() throws Exception {
            mockMvc.perform(post("/api/auth/refresh"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("동시에 같은 refresh 토큰으로 두 번 요청하면 정확히 하나만 성공한다")
        // 클래스 레벨 @Transactional은 테스트 스레드에만 바인딩된다 — 실제 동시성(별도 커넥션의 row lock)을
        // 검증하려면 이 테스트만 트랜잭션 밖에서 실행해 signup 데이터가 즉시 커밋되게 해야 한다.
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void refresh_concurrentRequestsWithSameToken_onlyOneSucceeds() throws Exception {
            MvcResult signup = signup("concurrent1@example.com", "password123", "tester");
            Cookie refreshCookie = refreshCookieOf(signup);

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger unauthorizedCount = new AtomicInteger();

            Runnable task = () -> {
                try {
                    ready.countDown();
                    start.await();
                    int status = mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status == 401) {
                        unauthorizedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            for (int i = 0; i < threadCount; i++) {
                executor.submit(task);
            }
            ready.await();
            start.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(unauthorizedCount.get()).isEqualTo(threadCount - 1);
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("로그아웃 후 같은 refresh 토큰으로 재발급을 시도하면 401을 반환한다")
        void logout_thenRefresh_returnsUnauthorized() throws Exception {
            MvcResult signup = signup("logout1@example.com", "password123", "tester");
            Cookie refreshCookie = refreshCookieOf(signup);

            mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().maxAge("refresh_token", 0));

            mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("cookie가 없어도 항상 204를 반환한다")
        void logout_withoutCookie_stillReturnsNoContent() throws Exception {
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("감사 시각")
    class Auditing {

        @Test
        @DisplayName("생성·수정 시각을 서버가 자동으로 기록한다")
        void userTimestamps_areManagedByJpaAuditing() throws Exception {
            mockMvc.perform(post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody("audit@example.com", "password123", "before")));

            User user = userRepository.findByEmail("audit@example.com").orElseThrow();
            assertThat(user.getCreatedAt()).isNotNull();
            assertThat(user.getUpdatedAt()).isNotNull();
            var initialUpdatedAt = user.getUpdatedAt();

            Thread.sleep(5);
            user.setNickname("after");
            userRepository.saveAndFlush(user);

            assertThat(user.getUpdatedAt()).isAfter(initialUpdatedAt);
        }
    }

    @Nested
    @DisplayName("CORS")
    class Cors {

        @Test
        @DisplayName("허용 Origin의 preflight 요청에는 credentials CORS 헤더를 반환한다")
        void preflight_allowedOrigin() throws Exception {
            mockMvc.perform(options("/api/auth/signup")
                            .header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }

        @Test
        @DisplayName("allowlist 밖 Origin에는 CORS 허용 헤더를 반환하지 않는다")
        void preflight_disallowedOrigin() throws Exception {
            mockMvc.perform(options("/api/auth/signup")
                            .header("Origin", "https://untrusted.example.com")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
