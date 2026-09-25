package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 카카오 Local 장애·timeout·잘못된 응답(docs/api/trip.md 5-6, 502). */
public class KakaoLocalUnavailableException extends TripException {
    public KakaoLocalUnavailableException() {
        super(ErrorCode.COURSE_KAKAO_LOCAL_UNAVAILABLE, "식당 검색 서비스에 일시적인 문제가 있습니다. 잠시 후 다시 시도해 주세요.");
    }
}
