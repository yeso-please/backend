package com.yeso.backend.attraction.domain;

/** 추첨 불가 사유(docs/api/attraction.md 7-1 ineligibleReasons). */
public enum IneligibleReason {
    NO_APPROVED_CONTENT,
    NO_VALID_HERO_IMAGE,
    INSUFFICIENT_ATTRACTIONS
}
