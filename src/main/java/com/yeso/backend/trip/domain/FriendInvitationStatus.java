package com.yeso.backend.trip.domain;

/** 친구 직접 초대의 상태(docs/api/trip.md 4-6). PENDING만 처리·취소할 수 있다. */
public enum FriendInvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    CANCELLED
}
