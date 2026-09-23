package com.yeso.backend.trip.presentation.invite;

/** guestSessionToken 원문은 이 응답에서만 반환한다 — 이후 요청은 Authorization: Bearer로 보낸다. */
public record JoinInviteResponse(Long participantId, String guestSessionToken, String displayName, String status) {
}
