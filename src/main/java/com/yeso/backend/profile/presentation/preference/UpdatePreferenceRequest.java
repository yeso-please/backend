package com.yeso.backend.profile.presentation.preference;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** @param courseTasteMode {@code TASTE} | {@code RANDOM} */
public record UpdatePreferenceRequest(
        @NotNull @Pattern(regexp = "TASTE|RANDOM", message = "TASTE 또는 RANDOM이어야 합니다.") String courseTasteMode) {
}
