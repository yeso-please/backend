package com.yeso.backend.shared.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;

/**
 * ai 서버 문장 API 연동. 임베딩과 같은 ai 서버({@code embedding.base-url})를 쓴다. 비어 있거나 {@code ai-text.enabled=false}면
 * 부르지 않는다. 생성 문장·프롬프트는 로그에 남기지 않고 실패 종류만 남긴다.
 */
@Slf4j
@Component
public class HttpAiTextClient implements AiTextClient {

    private final RestClient restClient;
    private final boolean enabled;

    public HttpAiTextClient(
            @Value("${embedding.base-url:}") String baseUrl,
            @Value("${ai-text.enabled:true}") boolean enabled,
            @Value("${ai-text.timeout-millis:15000}") int timeoutMillis) {
        this.enabled = enabled && baseUrl != null && !baseUrl.isBlank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 3000)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        this.restClient = RestClient.builder().baseUrl(baseUrl == null ? "" : baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<CourseExplanation> explainCourse(CourseExplanationRequest request) {
        return post("/explanations", request, CourseExplanation.class);
    }

    @Override
    public Optional<RegionMatch> matchRegion(RegionMatchRequest request) {
        return post("/explanations/region-match", request, RegionMatch.class);
    }

    private <T> Optional<T> post(String path, Object body, Class<T> type) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.post().uri(path).body(body).retrieve().body(type));
        } catch (RestClientResponseException e) {
            log.warn("AI text {} returned status={}", path, e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("AI text {} failed: {}", path, e.getClass().getSimpleName());
        }
        return Optional.empty();
    }
}
