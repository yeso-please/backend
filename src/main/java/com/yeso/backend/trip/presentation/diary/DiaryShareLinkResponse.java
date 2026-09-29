package com.yeso.backend.trip.presentation.diary;

import com.yeso.backend.trip.domain.DiaryShareLink;

import java.time.LocalDateTime;

public record DiaryShareLinkResponse(Long id, String token, LocalDateTime expiresAt, boolean revoked,
                                     LocalDateTime createdAt) {
    public static DiaryShareLinkResponse created(DiaryShareLink link, String token) {
        return new DiaryShareLinkResponse(link.getId(), token, link.getExpiresAt(), link.revoked(), link.getCreatedAt());
    }
    public static DiaryShareLinkResponse summary(DiaryShareLink link) {
        return new DiaryShareLinkResponse(link.getId(), null, link.getExpiresAt(), link.revoked(), link.getCreatedAt());
    }
}
