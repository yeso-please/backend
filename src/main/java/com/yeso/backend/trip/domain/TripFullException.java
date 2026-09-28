package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 참여자가 이미 정원({@link TripPlan#MAX_PARTICIPANTS}명)인 여행에 들어오려 할 때. */
public class TripFullException extends TripException {
    public TripFullException(Long tripId) {
        super(ErrorCode.TRIP_FULL, "참여 인원이 가득 찬 여행입니다: " + tripId);
    }
}
