package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** {@code mode=MANUAL}인데 {@code sigCd}가 없음(3-7). 요청 형식 위반으로 400을 준다. */
public class DrawSigCdRequiredException extends TripException {
    public DrawSigCdRequiredException() {
        super(ErrorCode.INVALID_REQUEST, "MANUAL은 sigCd가 필요합니다.");
    }
}
