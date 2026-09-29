package com.yeso.backend.trip.presentation.diary;

import com.yeso.backend.trip.domain.DiaryLocationPrecision;
import com.yeso.backend.trip.domain.DiaryStatus;
import com.yeso.backend.trip.domain.DiaryVisibility;

import java.time.LocalDate;

public record DiaryMapPinResponse(Long diaryId, String title, String courseTitle, String coverPhotoUrl,
                                  LocalDate visitedAt, Double lat, Double lng,
                                  DiaryLocationPrecision locationPrecision, DiaryVisibility visibility,
                                  DiaryStatus status) {}
