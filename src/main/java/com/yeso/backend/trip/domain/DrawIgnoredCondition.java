package com.yeso.backend.trip.domain;

/** 무시된 조건 하나(3-7 {@code ignoredConditions[]}). */
public record DrawIgnoredCondition(DrawCondition condition, DrawIgnoredReason reason) {
}
