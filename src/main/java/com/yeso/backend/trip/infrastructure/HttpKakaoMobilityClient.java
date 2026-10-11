package com.yeso.backend.trip.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 카카오모빌리티 자동차 길찾기({@code GET /v1/directions}) 연동. 키·좌표·응답 원문은 로그에 남기지 않는다 — 실패하면
 * 종류(상태 코드·result_code·예외 종류)와 지점 수만 남긴다.
 */
@Slf4j
@Component
public class HttpKakaoMobilityClient implements KakaoMobilityClient {

    private final RestClient restClient;
    private final RestaurantProperties.Kakao kakao;

    public HttpKakaoMobilityClient(RouteProperties route, RestaurantProperties restaurant) {
        this.kakao = restaurant.getKakao();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(route.getTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(route.getTimeoutMillis()));
        this.restClient = RestClient.builder().baseUrl(route.getBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public Optional<List<Leg>> route(List<Point> points) {
        if (points.size() < 2 || points.size() > 7) {
            throw new IllegalArgumentException("points must be 2..7: " + points.size());
        }
        String apiKey = kakao.getRestApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Kakao REST API key is not configured; road route skipped");
            return Optional.empty();
        }
        try {
            DirectionsResponse response = restClient.get()
                    .uri(builder -> uri(builder, points))
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .body(DirectionsResponse.class);
            return Optional.of(toLegs(response, points.size() - 1));
        } catch (RestClientResponseException e) {
            log.warn("Kakao Mobility returned status={} points={}", e.getStatusCode().value(), points.size());
        } catch (ResourceAccessException e) {
            log.warn("Kakao Mobility unreachable/timeout: {} points={}", e.getClass().getSimpleName(), points.size());
        } catch (UnusableRouteException e) {
            log.warn("Kakao Mobility unusable route: {} points={}", e.getMessage(), points.size());
        } catch (RestClientException e) {
            log.warn("Kakao Mobility malformed response: {} points={}", e.getClass().getSimpleName(), points.size());
        }
        return Optional.empty();
    }

    private static URI uri(UriBuilder builder, List<Point> points) {
        builder.path("/v1/directions")
                .queryParam("origin", coordinate(points.get(0)))
                .queryParam("destination", coordinate(points.get(points.size() - 1)))
                .queryParam("priority", "RECOMMEND");
        if (points.size() > 2) {
            builder.queryParam("waypoints", points.subList(1, points.size() - 1).stream()
                    .map(HttpKakaoMobilityClient::coordinate)
                    .collect(Collectors.joining("|")));
        }
        return builder.build();
    }

    private static String coordinate(Point point) {
        return point.lng() + "," + point.lat();
    }

    private static List<Leg> toLegs(DirectionsResponse response, int expected) {
        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            throw new UnusableRouteException("missing routes");
        }
        Route route = response.routes().get(0);
        if (route.resultCode() == null || route.resultCode() != 0) {
            throw new UnusableRouteException("result_code=" + route.resultCode());
        }
        int sections = route.sections() == null ? 0 : route.sections().size();
        if (sections != expected) {
            throw new UnusableRouteException("sections=" + sections + " expected=" + expected);
        }
        return route.sections().stream().map(section -> {
            if (section.distance() == null || section.duration() == null
                    || section.distance() < 0 || section.duration() < 0) {
                throw new UnusableRouteException("missing distance or duration");
            }
            return new Leg(section.distance(), section.duration(), path(section));
        }).toList();
    }

    /** 구간의 도로들({@code roads[].vertexes} = [x1, y1, x2, y2, …])을 순서대로 이어 위도·경도 목록으로 만든다. */
    private static List<Point> path(Section section) {
        List<Point> path = new ArrayList<>();
        if (section.roads() == null) {
            return path;
        }
        for (Road road : section.roads()) {
            List<Double> vertexes = road.vertexes();
            if (vertexes == null || vertexes.size() % 2 != 0) {
                throw new UnusableRouteException("malformed vertexes");
            }
            for (int i = 0; i < vertexes.size(); i += 2) {
                Point point = new Point(vertexes.get(i + 1), vertexes.get(i));
                // 이어지는 도로의 첫 점은 앞 도로의 끝 점과 같다. 같은 점을 두 번 넣지 않는다.
                if (path.isEmpty() || !path.get(path.size() - 1).equals(point)) {
                    path.add(point);
                }
            }
        }
        return path;
    }

    /** 응답은 왔지만 쓸 수 없다. 메시지에는 결과 코드·개수만 담는다(좌표·원문 금지). */
    private static final class UnusableRouteException extends RuntimeException {
        UnusableRouteException(String message) {
            super(message);
        }
    }

    private record DirectionsResponse(List<Route> routes) {
    }

    private record Route(@JsonProperty("result_code") Integer resultCode, List<Section> sections) {
    }

    private record Section(Integer distance, Integer duration, List<Road> roads) {
    }

    private record Road(List<Double> vertexes) {
    }
}
