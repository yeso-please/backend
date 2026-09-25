package com.yeso.backend.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 서비스가 "오늘"·"지금"을 판단할 때 쓰는 시계. {@code LocalDate.now()} 대신 {@code LocalDate.now(clock)}을 쓰면
 * 테스트가 시계를 고정·이동해 만료·종료를 검증할 수 있다. 서비스 기준 시간대는 Asia/Seoul이다.
 * 현재 trip 모듈만 주입받는다 — 다른 모듈은 해당 코드를 손볼 때 옮긴다.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
