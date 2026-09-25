package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 이미 수락·거절·취소된 친구 초대를 다시 처리하거나, 이미 참여 중인 사람을 초대할 때(docs/api/trip.md 4-6·4-8·4-15). */
public class InviteAlreadyHandledException extends InviteException {
    public InviteAlreadyHandledException() {
        super(ErrorCode.INVITE_ALREADY_HANDLED, "이미 처리된 초대입니다.");
    }

    public static InviteAlreadyHandledException alreadyParticipant() {
        return new InviteAlreadyHandledException("이미 여행에 참여 중인 친구입니다.");
    }

    private InviteAlreadyHandledException(String message) {
        super(ErrorCode.INVITE_ALREADY_HANDLED, message);
    }
}
