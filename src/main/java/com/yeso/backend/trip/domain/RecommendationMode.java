package com.yeso.backend.trip.domain;

/** 코스를 만든 방식. 취향을 쓸 수 없으면 공식 코스, 그다음 규칙 코스로 폴백한다. */
public enum RecommendationMode {
    PERSONALIZED,
    TOUR_OFFICIAL,
    RULE_BASED
}
