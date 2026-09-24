package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.domain.CourseShareLink;

import java.time.LocalDateTime;

public record ShareLinkResponse(Long id, String token, String permission, LocalDateTime expiresAt) {
    public static ShareLinkResponse of(CourseShareLink link, String token) {
        return new ShareLinkResponse(link.getId(), token, link.getPermission().name(), link.getExpiresAt());
    }
}
