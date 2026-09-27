package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 지역을 정하기 전에 코스를 만들려 한다. */
public class TripRegionNotSelectedException extends TripException {
    public TripRegionNotSelectedException(Long tripId) {
        super(ErrorCode.TRIP_REGION_NOT_SELECTED, "여행 지역을 먼저 정해야 합니다: " + tripId);
    }
}
