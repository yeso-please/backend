package com.yeso.backend.trip.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeso.backend.trip.domain.KakaoLocalRateLimitedException;
import com.yeso.backend.trip.domain.KakaoLocalUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * 카카오 Local REST API 연동. 키와 응답 전문은 로그에 남기지 않는다 — 실패하면 종류(상태 코드)만 남긴다.
 * 검색어가 없으면 카테고리 검색, 있으면 키워드 검색이며 둘 다 음식점(FD6)·거리순이다.
 */
@Slf4j
@Component
public class HttpKakaoLocalClient implements KakaoLocalClient {

    private static final String RESTAURANT_CATEGORY = "FD6";
    private static final int PAGE_SIZE = 15;

    private final RestClient restClient;
    private final RestaurantProperties.Kakao properties;

    public HttpKakaoLocalClient(RestaurantProperties properties) {
        this.properties = properties.getKakao();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(this.properties.getTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(this.properties.getTimeoutMillis()));
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public Page searchRestaurants(Query query) {
        String apiKey = properties.getRestApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Kakao Local REST API key is not configured");
            throw new KakaoLocalUnavailableException();
        }
        try {
            SearchResponse response = restClient.get()
                    .uri(builder -> uri(builder, query))
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .body(SearchResponse.class);
            return toPage(response);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Kakao Local returned status={}", status);
            if (status == 429) {
                throw new KakaoLocalRateLimitedException(retryAfter(e.getResponseHeaders()));
            }
            throw new KakaoLocalUnavailableException();
        } catch (ResourceAccessException e) {
            log.warn("Kakao Local unreachable/timeout: {}", e.getClass().getSimpleName());
            throw new KakaoLocalUnavailableException();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("Kakao Local malformed response: {}", e.getClass().getSimpleName());
            throw new KakaoLocalUnavailableException();
        }
    }

    private static URI uri(UriBuilder builder, Query query) {
        boolean keyword = query.keyword() != null && !query.keyword().isBlank();
        builder.path(keyword ? "/v2/local/search/keyword.json" : "/v2/local/search/category.json");
        if (keyword) {
            builder.queryParam("query", query.keyword());
        }
        return builder
                .queryParam("category_group_code", RESTAURANT_CATEGORY)
                .queryParam("x", query.lng())
                .queryParam("y", query.lat())
                .queryParam("radius", query.radiusMeters())
                .queryParam("sort", "distance")
                .queryParam("page", query.page())
                .queryParam("size", PAGE_SIZE)
                .build();
    }

    private static Page toPage(SearchResponse response) {
        if (response == null || response.meta() == null || response.documents() == null) {
            throw new IllegalArgumentException("missing meta or documents");
        }
        List<Place> places = response.documents().stream().map(HttpKakaoLocalClient::toPlace).toList();
        return new Page(places, response.meta().isEnd());
    }

    private static Place toPlace(Document document) {
        if (document.id() == null || document.x() == null || document.y() == null) {
            throw new IllegalArgumentException("missing id or coordinates");
        }
        Integer distance = document.distance() == null || document.distance().isBlank()
                ? null
                : Integer.valueOf(document.distance());
        return new Place(
                document.id(), document.placeName(), blankToNull(document.categoryName()),
                blankToNull(document.addressName()), blankToNull(document.roadAddressName()),
                blankToNull(document.phone()), Double.parseDouble(document.y()), Double.parseDouble(document.x()),
                distance, blankToNull(document.placeUrl()));
    }

    private Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value != null) {
            try {
                long seconds = Long.parseLong(value.trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // HTTP-date 형식이면 기본값을 쓴다.
            }
        }
        return Duration.ofSeconds(properties.getDefaultRetryAfterSeconds());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SearchResponse(Meta meta, List<Document> documents) {
    }

    private record Meta(@JsonProperty("is_end") boolean isEnd) {
    }

    private record Document(
            String id,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            String phone,
            String x,
            String y,
            String distance,
            @JsonProperty("place_url") String placeUrl) {
    }
}
