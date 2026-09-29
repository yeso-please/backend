package com.yeso.backend.trip.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiaryPhotoProperties.class)
public class DiaryPhotoStorageConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "tripin.diary.photos", name = "enabled", havingValue = "true")
    S3Client diaryPhotoS3Client(DiaryPhotoProperties properties) {
        requireBucket(properties);
        return S3Client.builder().region(Region.of(region(properties))).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "tripin.diary.photos", name = "enabled", havingValue = "true")
    S3Presigner diaryPhotoS3Presigner(DiaryPhotoProperties properties) {
        requireBucket(properties);
        return S3Presigner.builder().region(Region.of(region(properties))).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tripin.diary.photos", name = "enabled", havingValue = "true")
    S3DiaryPhotoStorage s3DiaryPhotoStorage(S3Client client, S3Presigner presigner, DiaryPhotoProperties properties) {
        return new S3DiaryPhotoStorage(client, presigner, properties.bucket());
    }

    @Bean
    @ConditionalOnMissingBean(DiaryPhotoStorage.class)
    UnavailableDiaryPhotoStorage unavailableDiaryPhotoStorage() {
        return new UnavailableDiaryPhotoStorage();
    }

    private static String region(DiaryPhotoProperties properties) {
        return properties.region() == null || properties.region().isBlank()
                ? "ap-northeast-2" : properties.region();
    }

    private static void requireBucket(DiaryPhotoProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException("tripin.diary.photos.bucket은 S3 저장소 활성화 시 필수입니다.");
        }
    }
}
