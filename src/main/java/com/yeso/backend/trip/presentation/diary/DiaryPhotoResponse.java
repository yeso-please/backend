package com.yeso.backend.trip.presentation.diary;

import java.time.LocalDateTime;

public record DiaryPhotoResponse(
        Long photoId, String url, String thumbnailUrl, LocalDateTime takenAt, Double lat, Double lng, int order) {
}
