package com.yeso.backend.trip.infrastructure;

import com.sun.net.httpserver.HttpServer;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 카카오맵 도보·대중교통 경로 조회 계약(응답 모양은 카카오 문서 기준). Docker 없이 JDK 내장 서버로 확인한다. */
class HttpKakaoMapRouteClientTest {

    private static final Point FROM = new Point(37.39477123, 127.11119217);
    private static final Point TO = new Point(37.40199, 127.10898);

    private HttpServer server;
    private RestaurantProperties restaurant;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        restaurant = new RestaurantProperties();
        restaurant.getKakao().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        restaurant.getKakao().setRestApiKey("test-kakao-key");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private HttpKakaoMapRouteClient client() {
        RouteProperties route = new RouteProperties();
        route.setTimeoutMillis(500);
        return new HttpKakaoMapRouteClient(route, restaurant);
    }

    @Test
    @DisplayName("도보: x=경도·y=위도로 보내고, 단계별 점을 [위도, 경도] 경로로 잇는다(이음점은 한 번)")
    void walk() {
        respond("/v2/routing/walk", 200, """
                {"status":"OK","route":{"properties":{"totalDistance":950,"totalTime":780,"landingUrl":"x"},
                 "legs":[{"properties":{"distance":950,"time":780},"steps":[
                   {"properties":{"distance":500},"path":{"points":[[127.11119,37.39477],[127.110,37.398]]}},
                   {"properties":{"distance":450},"path":{"points":[[127.110,37.398],[127.10898,37.40199]]}}]}]}}
                """);
        var leg = client().walk(FROM, TO);
        assertThat(leg).isPresent();
        assertThat(leg.get().distanceMeters()).isEqualTo(950);
        assertThat(leg.get().durationSeconds()).isEqualTo(780);
        assertThat(leg.get().path()).hasSize(3);
        assertThat(leg.get().path().get(0)).isEqualTo(new Point(37.39477, 127.11119));
        assertThat(lastQuery.get()).contains("start_x=127.11119217", "start_y=37.39477123", "end_x=127.10898");
        assertThat(lastAuthorization.get()).isEqualTo("KakaoAK test-kakao-key");
    }

    @Test
    @DisplayName("대중교통: 첫 경로의 거리·시간·환승·요금과 단계별 경로")
    void transit() {
        respond("/v2/routing/publictraffic", 200, """
                {"status":"OK","properties":{"total":2},"routes":[
                 {"properties":{"type":"BUS","totalDistance":1500,"totalTime":1080,"transfers":1,"fare":{"value":1500,"min":1500,"max":1700}},
                  "steps":[{"properties":{"type":"WALKING"},"path":{"points":[[127.11119,37.39477],[127.111,37.396]]}},
                           {"properties":{"type":"BUS"},"path":{"points":[[127.111,37.396],[127.10898,37.40199]]}}]},
                 {"properties":{"type":"SUBWAY","totalDistance":9,"totalTime":9,"transfers":0}}]}
                """);
        var leg = client().transit(FROM, TO);
        assertThat(leg).isPresent();
        assertThat(leg.get().leg().durationSeconds()).isEqualTo(1080);
        assertThat(leg.get().transfers()).isEqualTo(1);
        assertThat(leg.get().fare()).isEqualTo(1500);
        assertThat(leg.get().leg().path()).hasSize(3);
    }

    @Test
    @DisplayName("경로 없음·한도 초과(400)·키 없음이면 빈 값이다")
    void failures() {
        respond("/v2/routing/walk", 200, "{\"status\":\"TOO_FAR_AWAY\"}");
        respond("/v2/routing/publictraffic", 400, "{\"code\":-10,\"msg\":\"API limit has been exceeded.\"}");
        assertThat(client().walk(FROM, TO)).isEmpty();
        assertThat(client().transit(FROM, TO)).isEmpty();
        restaurant.getKakao().setRestApiKey("");
        assertThat(client().walk(FROM, TO)).isEmpty();
    }
}
