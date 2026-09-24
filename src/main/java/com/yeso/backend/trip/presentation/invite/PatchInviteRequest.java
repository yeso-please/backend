package com.yeso.backend.trip.presentation.invite;

/** null 필드는 "변경하지 않음"이다. revoked는 true로만 보낼 수 있고 되돌릴 수 없다. */
public record PatchInviteRequest(String permission, Boolean revoked) {
}
