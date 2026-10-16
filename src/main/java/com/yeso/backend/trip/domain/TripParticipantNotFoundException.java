package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 이 여행의 참여자가 아닌 사용자를 내보내려 함(3-10). */
public class TripParticipantNotFoundException extends TripException {
    public TripParticipantNotFoundException(Long userId) {
        super(ErrorCode.TRIP_PARTICIPANT_NOT_FOUND, "이 여행의 참여자가 아닙니다: " + userId);
    }
}
