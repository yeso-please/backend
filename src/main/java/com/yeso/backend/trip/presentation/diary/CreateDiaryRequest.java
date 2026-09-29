package com.yeso.backend.trip.presentation.diary;

import jakarta.validation.constraints.Size;

public record CreateDiaryRequest(@Size(max = 60) String title) {
}
