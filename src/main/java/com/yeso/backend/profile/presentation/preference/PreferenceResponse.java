package com.yeso.backend.profile.presentation.preference;

import com.yeso.backend.profile.domain.UserPreference;

/** @param courseTasteMode {@code TASTE}(취향 반영 랜덤, 기본) | {@code RANDOM}(완전 랜덤) */
public record PreferenceResponse(String courseTasteMode) {

    public static PreferenceResponse from(UserPreference preference) {
        return new PreferenceResponse(preference.getCourseTasteMode().name());
    }
}
