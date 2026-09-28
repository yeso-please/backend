package com.yeso.backend.trip.presentation.context;

import java.util.List;

public record DrawRegionResponse(
        Long tripId,
        String regionSigCd,
        String province,
        String city,
        String regionSelection,
        String scheduleDensity,
        List<String> appliedConditions,
        List<DrawIgnoredConditionResponse> ignoredConditions,
        long candidateCount,
        List<String> warnings,
        int version
) {
}
