package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchClient;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchRequest;
import com.yeso.backend.shared.embedding.AttractionEmbeddingBatchResponse;
import com.yeso.backend.shared.embedding.AttractionEmbeddingServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * 실제 Python 임베딩 서비스 연동. key/응답 전문은 로그에 남기지 않는다 — 실패 시 종류(코드)만 남긴다.
 * 운영 profile에서 이 bean 대신 fake/stub을 쓰는 것은 금지다(작업서 "운영 profile에서 fake vector 금지").
 */
@Slf4j
@Component
public class HttpEmbeddingClient implements EmbeddingClient, AttractionEmbeddingBatchClient {

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    public HttpEmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(clientHttpRequestFactory(properties.getTimeoutMillis()))
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory clientHttpRequestFactory(int timeoutMillis) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return factory;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) throws EmbeddingTransientException, EmbeddingPermanentException {
        try {
            EmbeddingApiResponse response = restClient.post()
                    .uri("/embeddings")
                    .body(request)
                    .retrieve()
                    .body(EmbeddingApiResponse.class);

            if (response == null || response.embeddingBase64() == null || response.embeddingBase64().isBlank()
                    || response.dimension() <= 0) {
                throw new EmbeddingPermanentException("MALFORMED_RESPONSE", "임베딩 응답 형식이 올바르지 않습니다.");
            }
            return new EmbeddingResult(response.embeddingBase64(), response.dimension());
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            log.warn("Embedding service returned status={}", status.value());
            if (status.value() == 429 || status.is5xxServerError()) {
                throw new EmbeddingTransientException("HTTP_" + status.value(), "임베딩 서비스 응답 오류", e);
            }
            throw new EmbeddingPermanentException("HTTP_" + status.value(), "임베딩 요청이 거부됐습니다.", e);
        } catch (ResourceAccessException e) {
            log.warn("Embedding service unreachable/timeout: {}", e.getClass().getSimpleName());
            throw new EmbeddingTransientException("TIMEOUT", "임베딩 서비스 호출이 시간 초과됐습니다.", e);
        }
    }

    @Override
    public AttractionEmbeddingBatchResponse embedAttractions(AttractionEmbeddingBatchRequest request) {
        try {
            AttractionEmbeddingBatchApiResponse response = restClient.post()
                    .uri("/embeddings/batch")
                    .body(request)
                    .retrieve()
                    .body(AttractionEmbeddingBatchApiResponse.class);
            if (response == null) {
                throw new AttractionEmbeddingServiceException("MALFORMED_RESPONSE", false, null);
            }
            return new AttractionEmbeddingBatchResponse(response.dimension(), response.items() == null ? null
                    : response.items().stream()
                            .map(item -> new AttractionEmbeddingBatchResponse.Item(item.id(), item.embeddingBase64()))
                            .toList());
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Attraction embedding batch returned status={}", status);
            throw new AttractionEmbeddingServiceException("HTTP_" + status, status == 429 || e.getStatusCode().is5xxServerError(), e);
        } catch (ResourceAccessException e) {
            log.warn("Attraction embedding batch unreachable/timeout: {}", e.getClass().getSimpleName());
            throw new AttractionEmbeddingServiceException("TIMEOUT", true, e);
        }
    }

    private record EmbeddingApiResponse(String embeddingBase64, int dimension) {
    }

    private record AttractionEmbeddingBatchApiResponse(int dimension, java.util.List<AttractionEmbeddingBatchApiItem> items) {
    }

    private record AttractionEmbeddingBatchApiItem(String id, String embeddingBase64) {
    }
}
