package com.yeso.backend.profile.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    private static EmbeddingRequest request() {
        return new EmbeddingRequest("req-1", new EmbeddingProfile("ENFP", "RELAXED", List.of("바다"), List.of("물놀이"),
                List.of(new EmbeddingProfile.LikedTrip("51150", "강원특별자치도 강릉시", List.of("바다"), "메모"))),
                "model", 1);
    }

    @Test
    @DisplayName("ai 계약대로 profile 객체를 보내고 벡터 응답을 읽는다")
    void embed_sendsStructuredProfileAndReadsResult() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        server.createContext("/embeddings", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"embeddingBase64\":\"AQID\",\"dimension\":384}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);

        EmbeddingResult result = client.embed(request());

        assertThat(result.dimension()).isEqualTo(384);
        assertThat(sent.get())
                .contains("\"requestId\":\"req-1\"", "\"modelVersion\":\"model\"", "\"templateVersion\":1")
                .contains("\"profile\":{\"travelMbti\":\"ENFP\",\"scheduleDensity\":\"RELAXED\"")
                .contains("\"likedTrips\":[{\"sigCd\":\"51150\",\"regionName\":\"강원특별자치도 강릉시\"")
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

        assertThatThrownBy(() -> client.embed(request()))
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

        assertThatThrownBy(() -> client.embed(request()))
                .isInstanceOf(EmbeddingPermanentException.class)
                .satisfies(e -> assertThat(((EmbeddingPermanentException) e).errorCode()).isEqualTo("HTTP_400"));
    }
}
