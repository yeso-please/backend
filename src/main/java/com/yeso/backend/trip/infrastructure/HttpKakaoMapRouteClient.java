package com.yeso.backend.trip.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 카카오맵 REST 경로 조회 연동(도보 {@code /v2/routing/walk}, 대중교통 {@code /v2/routing/publictraffic}). 식당 검색과 같은
 * 카카오 REST 키·호스트({@code course.restaurant.kakao})를 쓴다. 무료 한도는 각각 일 1,000건이고, 넘거나 실패하면 빈 값이다.
 * 키·좌표·응답 원문은 로그에 남기지 않고 실패 종류만 남긴다. 좌표는 WGS84, {@code x} = 경도, {@code y} = 위도다.
 */
@Slf4j
@Component
public class HttpKakaoMapRouteClient implements KakaoMapRouteClient {

    private static final String OK = "OK";

    private final RestClient restClient;
    private final RestaurantProperties.Kakao kakao;

    public HttpKakaoMapRouteClient(RouteProperties route, RestaurantProperties restaurant) {
        this.kakao = restaurant.getKakao();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(route.getTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(route.getTimeoutMillis()));
        this.restClient = RestClient.builder().baseUrl(kakao.getBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public Optional<Leg> walk(Point from, Point to) {
        return call("/v2/routing/walk", from, to, WalkResponse.class).flatMap(HttpKakaoMapRouteClient::toWalkLeg);
    }

    @Override
    public Optional<TransitLeg> transit(Point from, Point to) {
        return call("/v2/routing/publictraffic", from, to, TransitResponse.class).flatMap(HttpKakaoMapRouteClient::toTransitLeg);
    }

    private <T> Optional<T> call(String path, Point from, Point to, Class<T> type) {
        String apiKey = kakao.getRestApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Kakao REST API key is not configured; {} skipped", path);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(builder -> builder.path(path)
                            .queryParam("start_x", from.lng()).queryParam("start_y", from.lat())
                            .queryParam("end_x", to.lng()).queryParam("end_y", to.lat())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .body(type));
        } catch (RestClientResponseException e) {
            // 한도 초과는 400(code -10)으로 온다. 원문은 남기지 않는다.
            log.warn("Kakao Map route {} returned status={}", path, e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.warn("Kakao Map route {} unreachable/timeout: {}", path, e.getClass().getSimpleName());
        } catch (RestClientException e) {
            log.warn("Kakao Map route {} malformed response: {}", path, e.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    static Optional<Leg> toWalkLeg(WalkResponse response) {
        if (response == null || !OK.equals(response.status()) || response.route() == null
                || response.route().properties() == null) {
            log.info("Kakao walk route unusable: status={}", response == null ? null : response.status());
            return Optional.empty();
        }
        List<Point> path = new ArrayList<>();
        if (response.route().legs() != null) {
            for (WalkLeg leg : response.route().legs()) {
                if (leg.steps() != null) {
                    leg.steps().forEach(step -> append(path, step.path()));
                }
            }
        }
        Totals totals = response.route().properties();
        return Optional.of(new Leg(totals.totalDistance(), totals.totalTime(), List.copyOf(path)));
    }

    static Optional<TransitLeg> toTransitLeg(TransitResponse response) {
        if (response == null || !OK.equals(response.status()) || response.routes() == null || response.routes().isEmpty()
                || response.routes().get(0).properties() == null) {
            log.info("Kakao transit route unusable: status={}", response == null ? null : response.status());
            return Optional.empty();
        }
        TransitRoute route = response.routes().get(0);
        List<Point> path = new ArrayList<>();
        if (route.steps() != null) {
            route.steps().forEach(step -> append(path, step.path()));
        }
        TransitTotals totals = route.properties();
        return Optional.of(new TransitLeg(new Leg(totals.totalDistance(), totals.totalTime(), List.copyOf(path)),
                totals.transfers() == null ? 0 : totals.transfers(), totals.fare() == null ? null : totals.fare().value()));
    }

    /** 단계별 [x, y] 점을 [위도, 경도] 지점으로 이어 붙인다. 이음점이 겹치면 한 번만 넣는다. */
    private static void append(List<Point> path, PathPoints points) {
        if (points == null || points.points() == null) {
            return;
        }
        for (List<Double> xy : points.points()) {
            if (xy == null || xy.size() < 2) {
                continue;
            }
            Point point = new Point(xy.get(1), xy.get(0));
            if (path.isEmpty() || !path.get(path.size() - 1).equals(point)) {
                path.add(point);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalkResponse(String status, WalkRoute route) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalkRoute(Totals properties, List<WalkLeg> legs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalkLeg(List<Step> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Totals(int totalDistance, int totalTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Step(PathPoints path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathPoints(List<List<Double>> points) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransitResponse(String status, List<TransitRoute> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransitRoute(TransitTotals properties, List<Step> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransitTotals(int totalDistance, int totalTime, Integer transfers, Fare fare) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fare(Integer value) {
    }
}
