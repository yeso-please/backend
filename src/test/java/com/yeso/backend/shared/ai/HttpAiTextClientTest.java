package com.yeso.backend.shared.ai;

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

/** ai 서버 문장 API 계약(camelCase, 모르는 필드 무시, 실패는 빈 값). Docker 없이 JDK 내장 서버로 확인한다. */
class HttpAiTextClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/explanations", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"title":"바다를 걷는 하루","intro":"소개","reasons":[{"id":"a-1","reason":"이유","source":"llm","personalReason":null}],
                     "titleSource":"llm","introSource":"llm","aiGenerated":true,"promptVersion":"course-explain-v2",
                     "generatorModel":"gemini","exampleCourseIds":["1"]}
                    """);
        });
        server.createContext("/explanations/region-match", exchange -> respond(exchange, 503, "{}"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private HttpAiTextClient client(boolean enabled) {
        return new HttpAiTextClient("http://127.0.0.1:" + server.getAddress().getPort(), enabled, 2000);
    }

    @Test
    @DisplayName("코스 설명은 camelCase로 보내고, 응답의 모르는 필드는 무시한다")
    void explainCourse() {
        var result = client(true).explainCourse(new AiTextClient.CourseExplanationRequest("r", "강원특별자치도 강릉시", 1,
                List.of(new AiTextClient.Place("a-1", "경포해변", "NA", "NA02", "NA020900", "강원특별자치도 강릉시", "해변", 1, 1, List.of()))));
        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("바다를 걷는 하루");
        assertThat(result.get().reasons()).extracting(AiTextClient.Reason::source).containsExactly("llm");
        assertThat(lastBody.get()).contains("\"regionName\"", "\"lclsSystm3\":\"NA020900\"", "\"matchedFeatures\":[]");
    }

    @Test
    @DisplayName("ai 오류·꺼짐이면 빈 값이다(호출한 쪽이 규칙 문장을 쓴다)")
    void failures() {
        var request = new AiTextClient.RegionMatchRequest("r", "낭만적인 기록가", "한 줄", List.of(), "강릉", null, List.of(), List.of());
        assertThat(client(true).matchRegion(request)).isEmpty();
        assertThat(client(false).explainCourse(new AiTextClient.CourseExplanationRequest("r", "강릉", 1, List.of()))).isEmpty();
        assertThat(new HttpAiTextClient("", true, 1000).matchRegion(request)).isEmpty();
    }
}
