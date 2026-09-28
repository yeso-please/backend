package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.trip.domain.DrawIgnoredCondition;

public record DrawIgnoredConditionResponse(String condition, String reason) {
    public static DrawIgnoredConditionResponse from(DrawIgnoredCondition ignored) {
        return new DrawIgnoredConditionResponse(ignored.condition().name(), ignored.reason().name());
    }
}
