package com.yeso.backend.trip.domain;

/** 조건 추첨(3-7)에서 데이터가 없어 무시된 조건의 이유. */
public enum DrawIgnoredReason {
    /** 여행에 출발지가 없음(DISTANCE). */
    ORIGIN_MISSING,
    /** 요청자의 취향 벡터가 없거나 관광지 취향 벡터가 아직 준비되지 않음(MY_TASTE). */
    TASTE_NOT_READY
}
