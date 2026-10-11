package com.yeso.backend.trip.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 코스 이동값 도로 계산 설정(docs/api/trip.md 5장). 키는 {@code course.restaurant.kakao.rest-api-key}를 함께 쓴다. */
@Component
@ConfigurationProperties(prefix = "course.route")
@Getter
@Setter
public class RouteProperties {

    private String baseUrl = "https://apis-navi.kakaomobility.com";

    /** 길찾기 한 호출의 연결·읽기 timeout. */
    private int timeoutMillis = 2000;

    /** 요청 한 번(5-1·5-3·3-5)에서 길찾기에 쓰는 시간 상한. 넘기면 남은 구간은 추정값으로 둔다. */
    private int totalBudgetMillis = 4000;
}
