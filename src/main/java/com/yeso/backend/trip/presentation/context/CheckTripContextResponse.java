package com.yeso.backend.trip.presentation.context;

import java.time.LocalDate;
import java.util.List;

/** 3-2. {@code regionEligible}은 요청에 {@code sigCd}가 있을 때만 값이 있고, 없으면 null이다. */
public record CheckTripContextResponse(
        boolean available,
        LocalDate endDate,
        List<TripConflictResponse> conflicts,
        long eligibleRegionCount,
        Boolean regionEligible
) {
}
