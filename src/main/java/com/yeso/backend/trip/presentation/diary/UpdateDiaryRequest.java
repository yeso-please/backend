package com.yeso.backend.trip.presentation.diary;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateDiaryRequest(
        @Size(min = 1, max = 60) String title,
        @Size(max = 5000) String body,
        String visibility,
        String locationPrecision,
        @Min(1) @Max(5) Short satisfaction,
        @Size(max = 5) List<@Size(max = 30) String> experienceTags,
        Boolean includeInTasteProfile,
        Long coverPhotoId,
        List<Long> photoOrder) {
}
