package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 자기 자신은 내보낼 수 없다. 3-9 탈퇴를 쓴다(요청 형식 위반, 400). */
public class InvalidKickTargetException extends TripException {
    public InvalidKickTargetException() {
        super(ErrorCode.INVALID_REQUEST, "자기 자신은 내보낼 수 없습니다. 여행 탈퇴를 이용하세요.");
    }
}
