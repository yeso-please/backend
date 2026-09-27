package com.yeso.backend.trip.presentation.context;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record DrawRegionRequest(
        String mode,
        List<String> conditions,
        String sigCd,
        String scheduleDensity,
        Boolean replaceCourse,
        @NotNull(message = "version을 입력해주세요.") Integer version
) {
}
