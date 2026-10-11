package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docker 없이도 도는 순수 단위 테스트 — 실제 Python 서비스 대신 JDK 내장 HttpServer로
 * 5xx/타임아웃 응답을 흉내 낸다(FakeEmbeddingClient는 이 HTTP 계층을 거치지 않으므로 별도 커버리지 필요).
 */
class HttpEmbeddingClientTest {

    private HttpServer server;
    private EmbeddingProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new EmbeddingProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("AI Hub 프로필을 text가 아니라 versioned 구조화 profile로 전송한다")
    void embed_sendsStructuredProfileContract() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"embeddingBase64\":\"AQIDBA==\",\"dimension\":384}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);
        EmbeddingRequest request = new EmbeddingRequest("req-1", "mminilm-l12-v1", 2,
                new EmbeddingRequest.Profile("", "RELAXED", List.of(), List.of(), List.of(),
                        Map.of(1, 1, 3, 4), List.of(2, 7), List.of("서울특별시 종로구")));

        client.embed(request);

        assertThat(requestBody.get()).contains("\"templateVersion\":2")
                .contains("\"profile\":")
                .contains("\"travelStyles\":{")
                .contains("\"1\":1")
                .contains("\"3\":4")
                .contains("\"travelMotives\":[2,7]")
                .contains("\"likedRegions\":[\"서울특별시 종로구\"]")
                .doesNotContain("\"text\"");
    }

    @Test
    @DisplayName("서버가 500을 주면 HTTP_500 코드의 일시 장애 예외를 던진다")
    void embed_serverReturns500_throwsTransientExceptionWithHttpErrorCode() throws IOException {
        server.createContext("/embeddings", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);

        assertThatThrownBy(() -> client.embed(new EmbeddingRequest("req-1", "model", 1,
                new EmbeddingRequest.Profile("", "RELAXED", java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.Map.of(), java.util.List.of(), java.util.List.of()))))
                .isInstanceOf(EmbeddingTransientException.class)
                .satisfies(e -> assertThat(((EmbeddingTransientException) e).errorCode()).isEqualTo("HTTP_500"));
    }

    @Test
    @DisplayName("서버가 400을 주면 HTTP_400 코드의 영구 실패 예외를 던진다")
    void embed_serverReturns400_throwsPermanentExceptionWithHttpErrorCode() throws IOException {
        server.createContext("/embeddings", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);

        assertThatThrownBy(() -> client.embed(new EmbeddingRequest("req-1", "model", 1,
                new EmbeddingRequest.Profile("", "RELAXED", java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.Map.of(), java.util.List.of(), java.util.List.of()))))
                .isInstanceOf(EmbeddingPermanentException.class)
                .satisfies(e -> assertThat(((EmbeddingPermanentException) e).errorCode()).isEqualTo("HTTP_400"));
    }

    @Test
    @DisplayName("관광지 묶음을 /embeddings/batch로 보내고 ID별 벡터를 반환한다")
    void embedAttractions_sendsBatchContract() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/embeddings/batch", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"dimension":384,"items":[{"id":"101","embeddingBase64":"AQIDBA=="}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);
        var request = new AttractionEmbeddingBatchRequest("mminilm-l12-v1", 2, List.of(
                new AttractionEmbeddingBatchRequest.Item("101", "경복궁", 14, "서울특별시 종로구",
                        List.of("궁궐", "역사"), "조선의 법궁", "HS", "HS01", "HS010100")));

        var result = client.embedAttractions(request);

        assertThat(requestBody.get()).contains("\"templateVersion\":2")
                .contains("\"contentTypeId\":14")
                .contains("\"regionName\":\"서울특별시 종로구\"")
                .contains("\"description\":\"조선의 법궁\"")
                .contains("\"lclsSystm1\":\"HS\"")
                .contains("\"lclsSystm3\":\"HS010100\"");
        assertThat(result.dimension()).isEqualTo(384);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("101");
            assertThat(item.embeddingBase64()).isEqualTo("AQIDBA==");
        });
    }

    @Test
    @DisplayName("배치 API 일시 장애는 재실행 가능한 예외로 분류한다")
    void embedAttractions_serverReturns503_throwsRetryableException() throws IOException {
        server.createContext("/embeddings/batch", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);

        assertThatThrownBy(() -> client.embedAttractions(new AttractionEmbeddingBatchRequest(
                "mminilm-l12-v1", 2, List.of())))
                .isInstanceOf(com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException.class)
                .satisfies(exception -> assertThat(((com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException) exception)
                        .retryable()).isTrue());
    }
}
