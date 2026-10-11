package com.yeso.backend.auth.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yeso.backend.auth.domain.KakaoInvalidCodeException;
import com.yeso.backend.auth.domain.KakaoNotConfiguredException;
import com.yeso.backend.auth.domain.KakaoUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 카카오 대신 JDK 내장 HttpServer로 응답을 흉내 내는 단위 테스트(docs/conventions/테스트.md "외부 API").
 * 통합 테스트는 fake client를 쓰므로 HTTP 계층(요청 형식·상태 코드 매핑·재시도)은 여기서만 검증한다.
 */
class HttpKakaoOAuthClientTest {

    private static final String REDIRECT_URI = "http://localhost:5173/auth/kakao/callback";
    private static final String TOKEN_BODY = """
            {"token_type":"bearer","access_token":"kakao-access","expires_in":21599,
             "refresh_token":"kakao-refresh","refresh_token_expires_in":5183999}
            """;
    private static final String USER_BODY = """
            {"id":1234567890,"connected_at":"2026-10-09T00:00:00Z",
             "kakao_account":{"profile_nickname_needs_agreement":false,"profile":{"nickname":"카카오친구"}}}
            """;

    private HttpServer server;
    private KakaoOAuthProperties properties;
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastForm = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger userCalls = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // timeout 재시도 검증: 첫 요청 handler가 붙잡혀 있어도 두 번째 요청을 받도록 thread pool을 쓴다.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new KakaoOAuthProperties();
        properties.setAuthBaseUrl(baseUrl);
        properties.setApiBaseUrl(baseUrl);
        properties.setClientId("test-client-id");
        properties.setClientSecret("test-client-secret");
        properties.setConnectTimeoutMillis(500);
        properties.setTimeoutMillis(500);
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private void respondToken(int status, String body) {
        server.createContext("/oauth/token", exchange -> {
            tokenCalls.incrementAndGet();
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastForm.set(URLDecoder.decode(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
            send(exchange, status, body);
        });
    }

    private void respondUser(int... statuses) {
        server.createContext("/v2/user/me", exchange -> {
            int call = userCalls.getAndIncrement();
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = statuses[Math.min(call, statuses.length - 1)];
            send(exchange, status, status == 200 ? USER_BODY : "{}");
        });
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void hang(String path) {
        server.createContext(path, exchange -> {
            if (path.equals("/v2/user/me")) {
                userCalls.incrementAndGet();
            } else {
                tokenCalls.incrementAndGet();
            }
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
    }

    private HttpKakaoOAuthClient client() {
        return new HttpKakaoOAuthClient(properties);
    }

    @Test
    @DisplayName("토큰 교환은 form-urlencoded로 grant_type·client_id·client_secret·redirect_uri·code를 보내고 access token을 꺼낸다")
    void exchangeCode_sendsFormAndReturnsAccessToken() {
        respondToken(200, TOKEN_BODY);

        String accessToken = client().exchangeCode("auth-code", REDIRECT_URI);

        assertThat(accessToken).isEqualTo("kakao-access");
        assertThat(lastContentType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(lastForm.get().split("&")).containsExactlyInAnyOrder(
                "grant_type=authorization_code", "client_id=test-client-id", "client_secret=test-client-secret",
                "redirect_uri=" + REDIRECT_URI, "code=auth-code");
    }

    @Test
    @DisplayName("카카오가 400 invalid_grant면 인가 코드 오류이고 재시도하지 않는다")
    void exchangeCode_invalidGrant_throwsInvalidCode() {
        respondToken(400, """
                {"error":"invalid_grant","error_description":"authorization code not found","error_code":"KOE320"}
                """);

        assertThatThrownBy(() -> client().exchangeCode("used-code", REDIRECT_URI))
                .isInstanceOf(KakaoInvalidCodeException.class);
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid_grant가 아닌 4xx(잘못된 client 설정)·5xx·형식 오류는 장애이고 토큰 교환은 재시도하지 않는다")
    void exchangeCode_otherErrors_throwUnavailable() {
        respondToken(401, "{\"error\":\"invalid_client\",\"error_code\":\"KOE010\"}");
        assertThatThrownBy(() -> client().exchangeCode("code", REDIRECT_URI))
                .isInstanceOf(KakaoUnavailableException.class);

        server.removeContext("/oauth/token");
        respondToken(500, "oops");
        assertThatThrownBy(() -> client().exchangeCode("code", REDIRECT_URI))
                .isInstanceOf(KakaoUnavailableException.class);

        server.removeContext("/oauth/token");
        respondToken(200, "{\"token_type\":\"bearer\"}");
        assertThatThrownBy(() -> client().exchangeCode("code", REDIRECT_URI))
                .isInstanceOf(KakaoUnavailableException.class);

        assertThat(tokenCalls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("토큰 교환이 timeout이면 장애이고 재시도하지 않는다")
    void exchangeCode_timeout_throwsUnavailableWithoutRetry() {
        hang("/oauth/token");

        assertThatThrownBy(() -> client().exchangeCode("code", REDIRECT_URI))
                .isInstanceOf(KakaoUnavailableException.class);
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("client id나 secret이 없으면 호출하지 않고 미설정 오류다")
    void exchangeCode_withoutCredentials_throwsNotConfigured() {
        respondToken(200, TOKEN_BODY);
        properties.setClientSecret("");

        assertThatThrownBy(() -> client().exchangeCode("code", REDIRECT_URI))
                .isInstanceOf(KakaoNotConfiguredException.class);
        assertThat(tokenCalls.get()).isZero();
    }

    @Test
    @DisplayName("사용자 정보는 Bearer 토큰으로 조회하고 회원번호·닉네임을 꺼낸다")
    void fetchUser_returnsIdAndNickname() {
        respondUser(200);

        KakaoOAuthClient.KakaoUser user = client().fetchUser("kakao-access");

        assertThat(lastAuthorization.get()).isEqualTo("Bearer kakao-access");
        assertThat(user.id()).isEqualTo("1234567890");
        assertThat(user.nickname()).isEqualTo("카카오친구");
    }

    @Test
    @DisplayName("닉네임 동의가 없으면 닉네임은 null이다")
    void fetchUser_withoutProfile_nicknameNull() {
        server.createContext("/v2/user/me", exchange -> send(exchange, 200, "{\"id\":42,\"kakao_account\":{}}"));

        KakaoOAuthClient.KakaoUser user = client().fetchUser("kakao-access");

        assertThat(user.id()).isEqualTo("42");
        assertThat(user.nickname()).isNull();
    }

    @Test
    @DisplayName("사용자 정보 조회가 5xx면 1회 재시도한다")
    void fetchUser_serverErrorOnce_retriesAndSucceeds() {
        respondUser(500, 200);

        assertThat(client().fetchUser("kakao-access").id()).isEqualTo("1234567890");
        assertThat(userCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 정보 조회가 계속 5xx·timeout이면 2번 시도 후 장애다")
    void fetchUser_persistentFailure_throwsUnavailableAfterTwoAttempts() {
        respondUser(503);
        assertThatThrownBy(() -> client().fetchUser("kakao-access"))
                .isInstanceOf(KakaoUnavailableException.class);
        assertThat(userCalls.get()).isEqualTo(2);

        server.removeContext("/v2/user/me");
        userCalls.set(0);
        hang("/v2/user/me");
        assertThatThrownBy(() -> client().fetchUser("kakao-access"))
                .isInstanceOf(KakaoUnavailableException.class);
        assertThat(userCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("사용자 정보 조회가 4xx이거나 id가 없으면 재시도 없이 장애다")
    void fetchUser_clientErrorOrMalformed_throwsUnavailableWithoutRetry() {
        respondUser(401);
        assertThatThrownBy(() -> client().fetchUser("kakao-access"))
                .isInstanceOf(KakaoUnavailableException.class);
        assertThat(userCalls.get()).isEqualTo(1);

        server.removeContext("/v2/user/me");
        server.createContext("/v2/user/me", exchange -> send(exchange, 200, "{\"kakao_account\":{}}"));
        assertThatThrownBy(() -> client().fetchUser("kakao-access"))
                .isInstanceOf(KakaoUnavailableException.class);
    }
}
