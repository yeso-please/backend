package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 참여자 내보내기는 여행 관리자(생성자, 생성자가 나갔으면 가장 먼저 들어온 참여자)만 한다(3-10). */
public class TripManagerOnlyException extends TripException {
    public TripManagerOnlyException(Long tripId) {
        super(ErrorCode.TRIP_MANAGER_ONLY, "여행 관리자만 참여자를 내보낼 수 있습니다: " + tripId);
    }
}
