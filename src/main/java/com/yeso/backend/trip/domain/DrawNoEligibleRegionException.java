package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 이 일수·밀도로 추첨할 수 있는 지역이 하나도 없음(3-7 RANDOM·CONDITIONAL). */
public class DrawNoEligibleRegionException extends TripException {
    public DrawNoEligibleRegionException() {
        super(ErrorCode.DRAW_NO_ELIGIBLE_REGION, "추첨할 수 있는 지역이 없습니다.");
    }
}
