package com.yeso.backend.profile.presentation.friend;

import com.yeso.backend.profile.domain.FriendLink;

import java.time.LocalDateTime;

/** {@code token} 원문은 발급 응답에서만 반환한다. */
public record FriendLinkResponse(Long id, String token, LocalDateTime expiresAt, LocalDateTime createdAt) {

    public static FriendLinkResponse of(FriendLink link, String token) {
        return new FriendLinkResponse(link.getId(), token, link.getExpiresAt(), link.getCreatedAt());
    }
}
