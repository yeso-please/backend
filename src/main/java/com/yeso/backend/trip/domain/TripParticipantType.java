package com.yeso.backend.trip.domain;

/** OWNER는 여행을 만든 사람이다. 표시용일 뿐 권한 차이는 없다(2026-09-24 정책). */
public enum TripParticipantType {
    OWNER,
    MEMBER
}
