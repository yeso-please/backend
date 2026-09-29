package com.yeso.backend.trip.infrastructure;

import java.time.Duration;

public interface DiaryPhotoStorage {

    void put(String key, byte[] bytes, String contentType);

    String presignedGet(String key, Duration lifetime);

    void delete(String key);
}
