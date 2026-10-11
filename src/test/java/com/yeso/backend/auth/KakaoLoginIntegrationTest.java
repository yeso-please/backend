package com.yeso.backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.auth.infrastructure.FakeKakaoOAuthClient;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카카오 로그인(docs/api/auth.md 1-6). 카카오는 {@link FakeKakaoOAuthClient}가 대신한다 —
 * 테스트가 인가 코드마다 돌려줄 카카오 회원을 등록하고, 등록하지 않은 코드는 {@code invalid_grant}로 거부된다.
 */
class KakaoLoginIntegrationTest extends IntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:5173/auth/kakao/callback";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ResultActions kakaoLogin(String code) throws Exception {
        return kakaoLogin(code, REDIRECT_URI);
    }

    private ResultActions kakaoLogin(String code, String redirectUri) throws Exception {
        return mockMvc.perform(post("/api/auth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","redirectUri":"%s"}
                        """.formatted(code, redirectUri)));
    }

    private int userCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app.users", Integer.class);
    }

    private static Cookie refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).as("refresh_token cookie").isNotNull();
        return cookie;
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("처음 카카오로 들어오면 201로 소셜 전용 회원을 만들고 이메일 로그인과 같은 refresh cookie를 준다")
        void firstLogin_createsMember() throws Exception {
            fakeKakaoOAuthClient.register("code-1", "1234567890", "  카카오친구  ");

            kakaoLogin("code-1")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.id").isNumber())
                    .andExpect(jsonPath("$.user.email").value(nullValue()))
                    .andExpect(jsonPath("$.user.nickname").value("카카오친구"))
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.onboardingCompleted").value(false))
                    .andExpect(content().string(not(containsString("1234567890"))))
                    .andExpect(content().string(not(containsString("fake-kakao-token"))))
                    .andExpect(cookie().exists("refresh_token"))
                    .andExpect(cookie().httpOnly("refresh_token", true))
                    .andExpect(cookie().path("refresh_token", "/api/auth"))
                    .andExpect(cookie().secure("refresh_token", true))
                    .andExpect(cookie().sameSite("refresh_token", "Lax"));

            assertThat(jdbcTemplate.queryForList(
                    "SELECT provider, provider_user_id FROM app.social_accounts"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("provider")).isEqualTo("KAKAO");
                        assertThat(row.get("provider_user_id")).isEqualTo("1234567890");
                    });
            assertThat(jdbcTemplate.queryForList("SELECT email, password_hash FROM app.users"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("email")).isNull();
                        assertThat(row.get("password_hash")).isNull();
                    });
        }

        @Test
        @DisplayName("같은 카카오 회원이 다시 로그인하면 200이고 같은 회원이며 닉네임을 덮어쓰지 않는다")
        void secondLogin_returnsSameMemberWithoutOverwritingNickname() throws Exception {
            fakeKakaoOAuthClient.register("code-1", "1234567890", "처음이름");
            MvcResult first = kakaoLogin("code-1").andExpect(status().isCreated()).andReturn();
            Integer userId = JsonPath.read(first.getResponse().getContentAsString(), "$.user.id");

            fakeKakaoOAuthClient.register("code-2", "1234567890", "바뀐카카오이름");
            kakaoLogin("code-2")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(userId))
                    .andExpect(jsonPath("$.user.nickname").value("처음이름"))
                    .andExpect(cookie().exists("refresh_token"));

            assertThat(userCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("카카오 닉네임이 없으면 여행자+4자리, 30자를 넘으면 30자로 자른다")
        void nickname_defaultAndTruncate() throws Exception {
            fakeKakaoOAuthClient.register("code-empty", "111", null);
            kakaoLogin("code-empty")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.nickname").value(matchesPattern("여행자\\d{4}")));

            fakeKakaoOAuthClient.register("code-long", "222", "가".repeat(40));
            kakaoLogin("code-long")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.nickname").value("가".repeat(30)));
        }

        @Test
        @DisplayName("카카오 회원의 access token으로 내 정보를 조회하면 email은 null, loginMethods는 KAKAO다")
        void me_kakaoMember() throws Exception {
            fakeKakaoOAuthClient.register("code-1", "1234567890", "카카오친구");
            MvcResult login = kakaoLogin("code-1").andReturn();
            String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

            mockMvc.perform(get("/api/users/me").header("Authorization", ApiFixtures.bearer(accessToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(nullValue()))
                    .andExpect(jsonPath("$.nickname").value("카카오친구"))
                    .andExpect(jsonPath("$.loginMethods").value(contains("KAKAO")));
        }

        @Test
        @DisplayName("이메일 회원의 loginMethods는 EMAIL이다")
        void me_emailMember() throws Exception {
            ApiFixtures.Member member = fixtures.signup();

            mockMvc.perform(get("/api/users/me").header("Authorization", member.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.loginMethods").value(contains("EMAIL")));
        }

        @Test
        @DisplayName("카카오 회원도 refresh로 토큰을 회전하고 logout으로 세션을 끝낸다")
        void refreshAndLogout_kakaoMember() throws Exception {
            fakeKakaoOAuthClient.register("code-1", "1234567890", "카카오친구");
            MvcResult login = kakaoLogin("code-1").andReturn();
            Integer userId = JsonPath.read(login.getResponse().getContentAsString(), "$.user.id");

            MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookieOf(login)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(userId))
                    .andExpect(jsonPath("$.user.email").value(nullValue()))
                    .andReturn();
            Cookie rotated = refreshCookieOf(refreshed);

            mockMvc.perform(post("/api/auth/logout").cookie(rotated))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/auth/refresh").cookie(rotated))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
        }

        @Test
        @DisplayName("같은 카카오 회원의 첫 로그인이 동시에 두 번 와도 회원은 하나이고 둘 다 성공한다")
        void concurrentFirstLogin_createsOneMember() throws Exception {
            fakeKakaoOAuthClient.register("code-a", "1234567890", "카카오친구");
            fakeKakaoOAuthClient.register("code-b", "1234567890", "카카오친구");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Integer> statuses = new CopyOnWriteArrayList<>();
            for (String code : List.of("code-a", "code-b")) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        statuses.add(kakaoLogin(code).andReturn().getResponse().getStatus());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            ready.await();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            assertThat(userCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app.social_accounts", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("실패")
    class Failure {

        @Test
        @DisplayName("허용 목록에 없는 redirectUri는 카카오를 부르지 않고 400 AUTH_KAKAO_INVALID_REDIRECT_URI")
        void invalidRedirectUri() throws Exception {
            fakeKakaoOAuthClient.register("code-1", "1234567890", "카카오친구");

            kakaoLogin("code-1", "https://evil.example.com/auth/kakao/callback")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_KAKAO_INVALID_REDIRECT_URI"));

            kakaoLogin("code-1", REDIRECT_URI + "/extra")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_KAKAO_INVALID_REDIRECT_URI"));

            assertThat(fakeKakaoOAuthClient.exchangedCodes()).isEmpty();
        }

        @Test
        @DisplayName("카카오가 거부한 인가 코드나 이미 쓴 코드는 401 AUTH_KAKAO_INVALID_CODE")
        void invalidCode() throws Exception {
            kakaoLogin("unknown-code")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_KAKAO_INVALID_CODE"));

            fakeKakaoOAuthClient.register("code-1", "1234567890", "카카오친구");
            kakaoLogin("code-1").andExpect(status().isCreated());
            kakaoLogin("code-1")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_KAKAO_INVALID_CODE"));
        }

        @Test
        @DisplayName("카카오 장애면 502 AUTH_KAKAO_UNAVAILABLE이고 이메일 로그인은 영향이 없다")
        void kakaoUnavailable() throws Exception {
            fakeKakaoOAuthClient.setMode(FakeKakaoOAuthClient.Mode.UNAVAILABLE);

            kakaoLogin("code-1")
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("AUTH_KAKAO_UNAVAILABLE"));

            ApiFixtures.Member member = fixtures.signup();
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"%s"}
                                    """.formatted(member.email(), ApiFixtures.PASSWORD)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("서버에 카카오 키·secret이 없으면 503 AUTH_KAKAO_NOT_CONFIGURED")
        void notConfigured() throws Exception {
            fakeKakaoOAuthClient.setMode(FakeKakaoOAuthClient.Mode.NOT_CONFIGURED);

            kakaoLogin("code-1")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUTH_KAKAO_NOT_CONFIGURED"));
            assertThat(userCount()).isZero();
        }

        @Test
        @DisplayName("code가 없으면 400 COMMON_INVALID_REQUEST와 필드 오류")
        void missingCode() throws Exception {
            mockMvc.perform(post("/api/auth/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"redirectUri":"%s"}
                                    """.formatted(REDIRECT_URI)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
            assertThat(fakeKakaoOAuthClient.exchangedCodes()).isEmpty();
        }
    }
}
