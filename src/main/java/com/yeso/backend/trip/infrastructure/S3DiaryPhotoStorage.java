package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.shared.exception.ErrorCode;
import com.yeso.backend.trip.domain.DiaryException;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

@RequiredArgsConstructor
public class S3DiaryPhotoStorage implements DiaryPhotoStorage {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(bytes));
        } catch (RuntimeException exception) {
            throw new DiaryException(ErrorCode.DIARY_PHOTO_STORAGE_UNAVAILABLE, "사진을 저장할 수 없습니다.");
        }
    }

    @Override
    public String presignedGet(String key, Duration lifetime) {
        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(lifetime).getObjectRequest(request).build()).url().toExternalForm();
        } catch (RuntimeException exception) {
            throw new DiaryException(ErrorCode.DIARY_PHOTO_STORAGE_UNAVAILABLE, "사진 주소를 만들 수 없습니다.");
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException exception) {
            throw new DiaryException(ErrorCode.DIARY_PHOTO_STORAGE_UNAVAILABLE, "사진을 삭제할 수 없습니다.");
        }
    }
}
