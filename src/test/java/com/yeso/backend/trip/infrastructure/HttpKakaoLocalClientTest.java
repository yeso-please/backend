package com.yeso.backend.trip.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yeso.backend.trip.domain.KakaoLocalRateLimitedException;
import com.yeso.backend.trip.domain.KakaoLocalUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 카카오 대신 JDK 내장 HttpServer로 응답을 흉내 내는 단위 테스트(docs/conventions/테스트.md "외부 API").
 * 통합 테스트는 fake client를 쓰므로 HTTP 계층(경로·파라미터·상태 코드 매핑)은 여기서만 검증한다.
 */
class HttpKakaoLocalClientTest {

    private static final String SEARCH_BODY = """
            {"meta":{"is_end":false,"pageable_count":45,"total_count":120},
             "documents":[{"id":"12345678","place_name":"○○칼국수","category_name":"음식점 > 한식 > 국수",
               "address_name":"경북 경주시 황남동 1","road_address_name":"경북 경주시 포석로 1","phone":"",
               "x":"129.21","y":"35.84","distance":"380","place_url":"https://place.map.kakao.com/12345678"}]}
            """;

    private HttpServer server;
    private RestaurantProperties properties;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new RestaurantProperties();
        properties.getKakao().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getKakao().setRestApiKey("test-kakao-key");
        properties.getKakao().setTimeoutMillis(500);
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private void respond(int status, String body, String retryAfter) {
        server.createContext("/v2/local/search", exchange -> {
            record(exchange);
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void record(HttpExchange exchange) {
        lastPath.set(exchange.getRequestURI().getPath());
        lastQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private KakaoLocalClient.Page search(String keyword) {
        return new HttpKakaoLocalClient(properties)
                .searchRestaurants(new KakaoLocalClient.Query(keyword, 35.838, 129.211, 5000, 2));
    }

    @Test
    @DisplayName("검색어가 있으면 키워드 검색을 음식점·거리순으로 부르고 표시 필드를 옮긴다")
    void search_withKeyword_callsKeywordSearchAndMapsPlaces() {
        respond(200, SEARCH_BODY, null);

        KakaoLocalClient.Page page = search("칼국수");

        assertThat(lastPath.get()).isEqualTo("/v2/local/search/keyword.json");
        assertThat(lastQuery.get()).contains("query=칼국수", "category_group_code=FD6", "x=129.211", "y=35.838",
                "radius=5000", "sort=distance", "page=2", "size=15");
        assertThat(lastAuthorization.get()).isEqualTo("KakaoAK test-kakao-key");
        assertThat(page.isEnd()).isFalse();
        assertThat(page.places()).singleElement().satisfies(place -> {
            assertThat(place.id()).isEqualTo("12345678");
            assertThat(place.name()).isEqualTo("○○칼국수");
            assertThat(place.categoryName()).isEqualTo("음식점 > 한식 > 국수");
            assertThat(place.roadAddress()).isEqualTo("경북 경주시 포석로 1");
            assertThat(place.phone()).isNull();
            assertThat(place.lat()).isEqualTo(35.84);
            assertThat(place.lng()).isEqualTo(129.21);
            assertThat(place.distanceMeters()).isEqualTo(380);
            assertThat(place.placeUrl()).isEqualTo("https://place.map.kakao.com/12345678");
        });
    }

    @Test
    @DisplayName("검색어가 없으면 음식점 카테고리 검색을 부른다")
    void search_withoutKeyword_callsCategorySearch() {
        respond(200, SEARCH_BODY, null);

        search(null);

        assertThat(lastPath.get()).isEqualTo("/v2/local/search/category.json");
        assertThat(lastQuery.get()).doesNotContain("query=").contains("category_group_code=FD6");
    }

    @Test
    @DisplayName("429면 Retry-After를 담은 호출 한도 예외, 헤더가 없으면 기본 대기 시간이다")
    void search_rateLimited_throwsRateLimitedWithRetryAfter() {
        respond(429, "{}", "30");
        assertThatThrownBy(() -> search("칼국수"))
                .isInstanceOfSatisfying(KakaoLocalRateLimitedException.class,
                        e -> assertThat(e.getRetryAfter()).contains(Duration.ofSeconds(30)));

        server.removeContext("/v2/local/search");
        respond(429, "{}", null);
        assertThatThrownBy(() -> search("칼국수"))
                .isInstanceOfSatisfying(KakaoLocalRateLimitedException.class,
                        e -> assertThat(e.getRetryAfter()).contains(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("5xx·401이면 장애 예외다")
    void search_serverOrAuthError_throwsUnavailable() {
        respond(500, "{}", null);
        assertThatThrownBy(() -> search("칼국수")).isInstanceOf(KakaoLocalUnavailableException.class);

        server.removeContext("/v2/local/search");
        respond(401, "{}", null);
        assertThatThrownBy(() -> search("칼국수")).isInstanceOf(KakaoLocalUnavailableException.class);
    }

    @Test
    @DisplayName("응답 형식이 틀리면 장애 예외다")
    void search_malformedResponse_throwsUnavailable() {
        respond(200, "{\"documents\":[]}", null);
        assertThatThrownBy(() -> search("칼국수")).isInstanceOf(KakaoLocalUnavailableException.class);

        server.removeContext("/v2/local/search");
        respond(200, "not-json", null);
        assertThatThrownBy(() -> search("칼국수")).isInstanceOf(KakaoLocalUnavailableException.class);
    }

    @Test
    @DisplayName("응답이 timeout을 넘기면 장애 예외다")
    void search_timeout_throwsUnavailable() {
        server.createContext("/v2/local/search", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        assertThatThrownBy(() -> search("칼국수")).isInstanceOf(KakaoLocalUnavailableException.class);
    }

    @Test
    @DisplayName("REST API 키가 없으면 호출하지 않고 장애 예외다")
    void search_withoutApiKey_throwsUnavailableWithoutCalling() {
        respond(200, SEARCH_BODY, null);
        properties.getKakao().setRestApiKey("");

        assertThatThrownBy(() -> search("칼국수")).isInstanceOf(KakaoLocalUnavailableException.class);
        assertThat(lastPath.get()).isNull();
    }
}
