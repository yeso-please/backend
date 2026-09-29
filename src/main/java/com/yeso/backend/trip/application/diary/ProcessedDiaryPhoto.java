package com.yeso.backend.trip.application.diary;

import java.time.LocalDateTime;

public record ProcessedDiaryPhoto(byte[] original, byte[] thumbnail, String contentType,
                                  LocalDateTime takenAt, Double latitude, Double longitude) {
}
