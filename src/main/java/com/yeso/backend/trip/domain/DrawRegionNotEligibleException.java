package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** {@code MANUAL}로 고른 지역이 이 일수·밀도로 코스를 만들 수 없음(3-7). */
public class DrawRegionNotEligibleException extends TripException {
    public DrawRegionNotEligibleException(String sigCd) {
        super(ErrorCode.DRAW_REGION_NOT_ELIGIBLE, "이 지역은 지금 코스를 만들 수 없습니다: " + sigCd);
    }
}
