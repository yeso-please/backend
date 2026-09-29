package com.yeso.backend.trip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "diary_photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiaryPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diary_id", nullable = false)
    private TravelDiary diary;

    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    @Column(name = "thumbnail_key", nullable = false, unique = true, length = 500)
    private String thumbnailKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "taken_at")
    private LocalDateTime takenAt;

    private Double latitude;
    private Double longitude;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    public DiaryPhoto(TravelDiary diary, String objectKey, String thumbnailKey, String contentType, long byteSize,
                      LocalDateTime takenAt, Double latitude, Double longitude, int orderIndex) {
        this.diary = diary;
        this.objectKey = objectKey;
        this.thumbnailKey = thumbnailKey;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.takenAt = takenAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.orderIndex = orderIndex;
    }

    public void changeOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }
}
