package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

import java.util.List;
import java.util.Map;

public class TripDateOverlapException extends TripException {
    public TripDateOverlapException(List<TripConflict> conflicts) {
        super(ErrorCode.TRIP_DATE_OVERLAP, "이미 확정된 여행과 날짜가 겹칩니다.", Map.of("conflicts", conflicts));
    }
}
