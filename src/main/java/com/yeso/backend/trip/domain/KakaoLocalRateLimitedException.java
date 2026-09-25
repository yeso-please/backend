package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

import java.time.Duration;
import java.util.Optional;

/** 카카오 Local 호출 한도 초과(docs/api/trip.md 5-6, 503 + {@code Retry-After}). */
public class KakaoLocalRateLimitedException extends TripException {

    private final Duration retryAfter;

    public KakaoLocalRateLimitedException(Duration retryAfter) {
        super(ErrorCode.COURSE_KAKAO_LOCAL_RATE_LIMITED, "식당 검색 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    @Override
    public Optional<Duration> getRetryAfter() {
        return Optional.of(retryAfter);
    }
}
