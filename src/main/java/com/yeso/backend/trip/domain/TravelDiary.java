package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.shared.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "travel_diaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TravelDiary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiaryStatus status = DiaryStatus.DRAFT;

    @Column(length = 60)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body = "";

    @Column(name = "course_title", nullable = false, length = 120)
    private String courseTitle;

    @Column(name = "region_sig_cd", length = 5)
    private String regionSigCd;

    @Column(name = "visited_from", nullable = false)
    private LocalDate visitedFrom;

    @Column(name = "visited_to", nullable = false)
    private LocalDate visitedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiaryVisibility visibility = DiaryVisibility.PRIVATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_precision", nullable = false, length = 20)
    private DiaryLocationPrecision locationPrecision = DiaryLocationPrecision.CITY;

    private Short satisfaction;

    @Column(name = "experience_tags", nullable = false, columnDefinition = "text")
    private String experienceTagsJson = "[]";

    @Column(name = "include_in_taste_profile", nullable = false)
    private boolean includeInTasteProfile = true;

    @Column(name = "cover_photo_id")
    private Long coverPhotoId;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public TravelDiary(Long tripId, User author, String title, String courseTitle, String regionSigCd,
                       LocalDate visitedFrom, LocalDate visitedTo) {
        this.tripId = tripId;
        this.author = author;
        this.title = title;
        this.courseTitle = courseTitle;
        this.regionSigCd = regionSigCd;
        this.visitedFrom = visitedFrom;
        this.visitedTo = visitedTo;
    }

    public void update(String title, String body, DiaryVisibility visibility, DiaryLocationPrecision locationPrecision,
                       Short satisfaction, String experienceTagsJson, Boolean includeInTasteProfile, Long coverPhotoId) {
        if (title != null) this.title = title;
        if (body != null) this.body = body;
        if (visibility != null) this.visibility = visibility;
        if (locationPrecision != null) this.locationPrecision = locationPrecision;
        if (satisfaction != null) this.satisfaction = satisfaction;
        if (experienceTagsJson != null) this.experienceTagsJson = experienceTagsJson;
        if (includeInTasteProfile != null) this.includeInTasteProfile = includeInTasteProfile;
        if (coverPhotoId != null) this.coverPhotoId = coverPhotoId;
    }

    public void publish(LocalDateTime at) {
        this.status = DiaryStatus.PUBLISHED;
        if (this.publishedAt == null) this.publishedAt = at;
    }

    public void setCoverPhotoId(Long coverPhotoId) {
        this.coverPhotoId = coverPhotoId;
    }
}
