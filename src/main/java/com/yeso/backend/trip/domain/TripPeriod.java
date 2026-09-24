package com.yeso.backend.trip.domain;

/** 내 여행 목록 필터. 종료일이 오늘 이후면 UPCOMING, 지났으면 PAST다. */
public enum TripPeriod {
    UPCOMING,
    PAST
}
