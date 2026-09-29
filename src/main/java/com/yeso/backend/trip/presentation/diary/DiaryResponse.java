package com.yeso.backend.trip.presentation.diary;

import com.yeso.backend.trip.domain.DiaryLocationPrecision;
import com.yeso.backend.trip.domain.DiaryStatus;
import com.yeso.backend.trip.domain.DiaryVisibility;
import com.yeso.backend.trip.domain.TravelDiary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DiaryResponse(
        Long diaryId, Long tripId, DiaryStatus status, String title, String body, String courseTitle,
        String regionSigCd, LocalDate visitedFrom, LocalDate visitedTo, DiaryVisibility visibility,
        DiaryLocationPrecision locationPrecision, Short satisfaction, List<String> experienceTags,
        Boolean includeInTasteProfile, Long coverPhotoId, List<DiaryPhotoResponse> photos,
        LocalDateTime publishedAt, LocalDateTime updatedAt) {

    public static DiaryResponse of(TravelDiary diary, List<DiaryPhotoResponse> photos, List<String> experienceTags,
                                   boolean includeInTasteProfile) {
        return new DiaryResponse(diary.getId(), diary.getTripId(), diary.getStatus(), diary.getTitle(), diary.getBody(),
                diary.getCourseTitle(), diary.getRegionSigCd(), diary.getVisitedFrom(), diary.getVisitedTo(),
                diary.getVisibility(), diary.getLocationPrecision(), diary.getSatisfaction(), experienceTags,
                includeInTasteProfile ? diary.isIncludeInTasteProfile() : null,
                diary.getCoverPhotoId(), photos, diary.getPublishedAt(), diary.getUpdatedAt());
    }
}
