package com.yeso.backend.trip.domain;

/** 코스 이동값의 출처(docs/api/trip.md 5장 {@code travelSource}). */
public enum TravelSource {
    /** 카카오모빌리티 자동차 길찾기 도로 값. */
    ROAD,
    /** 직선거리 추정({@link TravelTimeEstimator}). */
    ESTIMATE
}
