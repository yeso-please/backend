package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 여행을 만든 뒤에는 시작일·종료일을 바꿀 수 없다. 요청 형식 위반으로 400을 준다. */
public class TripDatesImmutableException extends TripException {
    public TripDatesImmutableException() {
        super(ErrorCode.INVALID_REQUEST, "여행 날짜는 생성 후 변경할 수 없습니다.");
    }
}
