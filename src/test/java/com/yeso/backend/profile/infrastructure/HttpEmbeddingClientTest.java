package com.yeso.backend.profile.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

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
    @DisplayName("서버가 500을 주면 HTTP_500 코드의 일시 장애 예외를 던진다")
    void embed_serverReturns500_throwsTransientExceptionWithHttpErrorCode() throws IOException {
        server.createContext("/embeddings", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpEmbeddingClient client = new HttpEmbeddingClient(properties);

        assertThatThrownBy(() -> client.embed(new EmbeddingRequest("req-1", "text", "model", 1)))
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

        assertThatThrownBy(() -> client.embed(new EmbeddingRequest("req-1", "text", "model", 1)))
                .isInstanceOf(EmbeddingPermanentException.class)
                .satisfies(e -> assertThat(((EmbeddingPermanentException) e).errorCode()).isEqualTo("HTTP_400"));
    }
}
