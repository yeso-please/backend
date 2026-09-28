package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 존재하지 않는 trip과 타인 소유 trip 접근을 구분하지 않고 같은 404로 응답한다(존재 여부 비노출). */
public class TripNotFoundException extends TripException {
    public TripNotFoundException(Long tripId) {
        super(ErrorCode.TRIP_NOT_FOUND, "여행을 찾을 수 없습니다: " + tripId);
    }
}
