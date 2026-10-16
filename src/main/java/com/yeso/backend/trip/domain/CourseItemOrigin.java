package com.yeso.backend.trip.domain;

/**
 * 기준점(코스를 생성·재생성한 시점의 코스) 대비 관광지 항목의 출처(docs/api/trip.md 5장 "변경 기록").
 * 재생성하면 모든 항목이 다시 {@code BASE}가 된다.
 */
public enum CourseItemOrigin {
    /** 기준점 코스의 항목(되살린 기준점 항목 포함). */
    BASE,
    /** 기준점 이후 추가했거나, 추가한 항목을 다시 교체한 것. */
    ADDED,
    /** 기준점 항목을 다른 장소로 바꾼 것. */
    REPLACED
}
