package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.shared.exception.ErrorCode;
import com.yeso.backend.trip.domain.DiaryException;

import java.time.Duration;

public class UnavailableDiaryPhotoStorage implements DiaryPhotoStorage {

    private DiaryException unavailable() {
        return new DiaryException(ErrorCode.DIARY_PHOTO_STORAGE_UNAVAILABLE,
                "여행기 사진 저장소가 설정되지 않았습니다.");
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw unavailable();
    }

    @Override
    public String presignedGet(String key, Duration lifetime) {
        throw unavailable();
    }

    @Override
    public void delete(String key) {
        throw unavailable();
    }
}
