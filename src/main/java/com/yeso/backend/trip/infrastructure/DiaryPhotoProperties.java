package com.yeso.backend.trip.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripin.diary.photos")
public record DiaryPhotoProperties(String bucket, String region) {
}
