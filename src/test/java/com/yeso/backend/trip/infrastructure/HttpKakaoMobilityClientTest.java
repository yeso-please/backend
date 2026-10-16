package com.yeso.backend.trip.infrastructure;

import com.sun.net.httpserver.HttpServer;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 카카오 대신 JDK HttpServer로 응답을 흉내 낸다(docs/conventions/테스트.md "외부 API"). */
class HttpKakaoMobilityClientTest {

    private static final List<Point> THREE = List.of(
            new Point(36.5789, 128.7648), new Point(36.5680, 128.7300), new Point(36.5392, 128.5186));

    private HttpServer server;
    private RouteProperties route;
    private RestaurantProperties restaurant;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        route = new RouteProperties();
        route.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        route.setTimeoutMillis(500);
        restaurant = new RestaurantProperties();
        restaurant.getKakao().setRestApiKey("test-kakao-key");
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private void respond(int status, String body) {
        server.createContext("/v1/directions", exchange -> {
            calls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String routeBody(int resultCode, String sections) {
        return """
                {"trans_id":"t","routes":[{"result_code":%d,"result_msg":"m","summary":{"distance":1,"duration":1},
                 "sections":[%s]}]}
                """.formatted(resultCode, sections);
    }

    private KakaoMobilityClient client() {
        return new HttpKakaoMobilityClient(route, restaurant);
    }

    @Test
    @DisplayName("출발·경유·도착을 경도,위도로 보내고 구간 거리·시간·도로 경로를 순서대로 돌려준다")
    void success() {
        respond(200, routeBody(0, """
                {"distance":5987,"duration":631,"roads":[
                   {"vertexes":[128.7648,36.5789,128.75,36.57]},
                   {"vertexes":[128.75,36.57,128.73,36.568]}]},
                {"distance":25349,"duration":1669,"roads":[{"vertexes":[128.73,36.568,128.5186,36.5392]}]}"""));
        List<Leg> legs = client().route(THREE).orElseThrow();
        assertThat(legs).extracting(Leg::distanceMeters, Leg::durationSeconds)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(5987, 631), org.assertj.core.groups.Tuple.tuple(25349, 1669));
        // 이어지는 도로의 겹치는 점은 한 번만, [x, y]를 위도·경도로 바꿔서
        assertThat(legs.get(0).path()).containsExactly(
                new Point(36.5789, 128.7648), new Point(36.57, 128.75), new Point(36.568, 128.73));
        assertThat(legs.get(1).path()).containsExactly(new Point(36.568, 128.73), new Point(36.5392, 128.5186));
        assertThat(lastPath.get()).isEqualTo("/v1/directions");
        assertThat(lastQuery.get()).contains("origin=128.7648,36.5789", "destination=128.5186,36.5392",
                "waypoints=128.73,36.568");
        assertThat(lastAuthorization.get()).isEqualTo("KakaoAK test-kakao-key");
    }

    @Test
    @DisplayName("result_code가 0이 아니거나 구간 수가 다르면 빈 값이다")
    void unusableResponse() {
        respond(200, routeBody(104, "{\"distance\":1,\"duration\":1},{\"distance\":1,\"duration\":1}"));
        assertThat(client().route(THREE)).isEmpty();
        server.removeContext("/v1/directions");
        respond(200, routeBody(0, "{\"distance\":1,\"duration\":1}"));
        assertThat(client().route(THREE)).isEmpty();
        server.removeContext("/v1/directions");
        respond(200, routeBody(0, """
                {"distance":1,"duration":1,"roads":[{"vertexes":[128.7,36.5,128.6]}]},{"distance":1,"duration":1}"""));
        assertThat(client().route(THREE)).isEmpty();
    }

    @Test
    @DisplayName("429·500이면 빈 값이다")
    void errorStatus() {
        respond(429, "{}");
        assertThat(client().route(THREE)).isEmpty();
        server.removeContext("/v1/directions");
        respond(500, "{}");
        assertThat(client().route(THREE)).isEmpty();
    }

    @Test
    @DisplayName("timeout이면 빈 값이다")
    void timeout() {
        server.createContext("/v1/directions", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        assertThat(client().route(THREE)).isEmpty();
    }

    @Test
    @DisplayName("키가 없으면 호출하지 않고 빈 값이다")
    void blankKey() {
        respond(200, routeBody(0, "{\"distance\":1,\"duration\":1},{\"distance\":1,\"duration\":1}"));
        restaurant.getKakao().setRestApiKey(" ");
        assertThat(client().route(THREE)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("지점이 2~7개가 아니면 호출한 쪽의 버그다")
    void pointCount() {
        assertThatThrownBy(() -> client().route(List.of(THREE.get(0)))).isInstanceOf(IllegalArgumentException.class);
    }
}
