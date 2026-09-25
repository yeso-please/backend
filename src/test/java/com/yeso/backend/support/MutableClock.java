package com.yeso.backend.support;

import com.yeso.backend.shared.config.TimeConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 테스트용 시계. 기본값은 {@link #DEFAULT_NOW}(2026-10-01 10:00 Asia/Seoul)에 멈춰 있고,
 * 테스트가 {@link #advance}·{@link #setTo}로 옮긴다. {@link IntegrationTest}가 매 테스트 후 {@link #reset}한다.
 * 운영 {@code Clock} bean 대신 {@code @Primary}로 주입된다({@link TestInfraConfig}).
 */
public class MutableClock extends Clock {

    public static final ZoneId ZONE = TimeConfig.SERVICE_ZONE;
    public static final LocalDateTime DEFAULT_NOW = LocalDateTime.of(2026, 10, 1, 10, 0);

    private volatile Instant instant = defaultInstant();

    private static Instant defaultInstant() {
        return DEFAULT_NOW.atZone(ZONE).toInstant();
    }

    /** 현재 시계 기준 서비스 시간대의 오늘. 테스트 날짜는 이 값에서 상대적으로 만든다. */
    public LocalDate today() {
        return LocalDate.now(this);
    }

    /** 현재 시계 기준 서비스 시간대의 지금. */
    public LocalDateTime now() {
        return LocalDateTime.now(this);
    }

    public void setTo(LocalDateTime dateTime) {
        this.instant = dateTime.atZone(ZONE).toInstant();
    }

    /** 그 날짜의 10:00(서비스 시간대)로 옮긴다. 여행 종료처럼 날짜 단위 판정에 쓴다. */
    public void setTo(LocalDate date) {
        setTo(date.atTime(LocalTime.of(10, 0)));
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public void reset() {
        this.instant = defaultInstant();
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
