package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.CourseShareLink;

import java.time.LocalDateTime;

/** {@code token} 원문은 발급 응답에서만 반환한다. */
public record ShareLinkResponse(Long id, String token, LocalDateTime expiresAt, LocalDateTime createdAt) {

    public static ShareLinkResponse of(CourseShareLink link, String token) {
        return new ShareLinkResponse(link.getId(), token, link.getExpiresAt(), link.getCreatedAt());
    }
}
