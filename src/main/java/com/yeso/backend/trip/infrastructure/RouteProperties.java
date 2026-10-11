package com.yeso.backend.trip.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 코스 경로(docs/api/trip.md 5-7) 설정. 키는 {@code course.restaurant.kakao.rest-api-key}를 함께 쓴다. */
@Component
@ConfigurationProperties(prefix = "course.route")
@Getter
@Setter
public class RouteProperties {

    private String baseUrl = "https://apis-navi.kakaomobility.com";

    /** 길찾기 한 호출의 연결·읽기 timeout. */
    private int timeoutMillis = 2000;

    /** 경로 요청 한 번에서 길찾기에 쓰는 시간 상한. 넘기면 남은 구간은 직선으로 내려준다. */
    private int totalBudgetMillis = 4000;

    /** 구간 결과를 서버 메모리에 기억하는 시간. 같은 두 지점은 이 시간 동안 카카오를 다시 부르지 않는다. */
    private int cacheTtlMinutes = 1440;

    /** 기억하는 구간 수 상한. 넘치면 가장 오래 안 쓴 구간부터 버린다. */
    private int cacheMaxEntries = 5000;
}
