package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** {@code CONDITIONAL}인데 유효한 조건이 하나도 없음(3-7). */
public class DrawNoConditionSelectedException extends TripException {
    public DrawNoConditionSelectedException() {
        super(ErrorCode.DRAW_NO_CONDITION_SELECTED, "CONDITIONAL은 DISTANCE, MY_TASTE 중 1개 이상을 선택해야 합니다.");
    }
}
