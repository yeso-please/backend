package com.yeso.backend.profile.presentation.friend;

import com.yeso.backend.profile.domain.FriendLink;

import java.time.LocalDateTime;

/** 내 친구 초대 링크 목록의 원소. 만료·폐기된 링크도 포함한다. */
public record FriendLinkSummaryResponse(
        Long id, LocalDateTime expiresAt, boolean revoked, int acceptedCount, LocalDateTime createdAt) {

    public static FriendLinkSummaryResponse from(FriendLink link) {
        return new FriendLinkSummaryResponse(
                link.getId(), link.getExpiresAt(), link.isRevoked(), link.getAcceptedCount(), link.getCreatedAt());
    }
}
