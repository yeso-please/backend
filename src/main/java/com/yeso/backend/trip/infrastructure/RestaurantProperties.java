package com.yeso.backend.trip.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 식당 추천·검색·선택 설정(docs/api/trip.md 5-3·5-5·5-6). 비밀값(서명 키, 카카오 키)은 커밋하지 않고
 * {@code ./config/application-secret.yaml} 또는 환경변수로 넣는다. 로그에 남기지 않는다.
 */
@Component
@ConfigurationProperties(prefix = "course.restaurant")
@Getter
@Setter
public class RestaurantProperties {

    /**
     * {@code selectionToken}(rs_) HMAC-SHA256 서명 키. 32 byte 이상. 비어 있어도 기동은 막지 않고 식당 선택 기능만
     * 실패한다 — 커밋된 기본값으로 누구나 위조 가능한 키가 쓰이는 일이 없도록 기본값을 두지 않는다.
     */
    private String selectionSecret = "";

    private Kakao kakao = new Kakao();

    @Getter
    @Setter
    public static class Kakao {

        private String baseUrl = "https://dapi.kakao.com";

        /** 카카오 REST API 키. 비어 있으면 검색이 {@code COURSE_KAKAO_LOCAL_UNAVAILABLE}로 실패한다. */
        private String restApiKey = "";

        private int timeoutMillis = 3000;

        /** 카카오가 한도 초과(429)에 {@code Retry-After}를 주지 않을 때 쓰는 대기 시간. */
        private int defaultRetryAfterSeconds = 60;
    }
}
