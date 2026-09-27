package com.yeso.backend.profile.application.preference;

import com.yeso.backend.profile.domain.CourseTasteMode;
import com.yeso.backend.profile.domain.UserPreference;
import com.yeso.backend.profile.infrastructure.UserPreferenceRepository;
import com.yeso.backend.profile.presentation.preference.PreferenceResponse;
import com.yeso.backend.profile.presentation.preference.UpdatePreferenceRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 설정(docs/api/profile.md 2-11·2-12). 코스 생성(trip 5-1)은 {@link #courseTasteModeOf}로 기본값을 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;

    @Transactional(readOnly = true)
    public PreferenceResponse get(Long userId) {
        return PreferenceResponse.from(find(userId));
    }

    public PreferenceResponse update(Long userId, UpdatePreferenceRequest request) {
        UserPreference preference = userPreferenceRepository.findById(userId)
                .orElseGet(() -> UserPreference.defaultsFor(userId));
        preference.changeCourseTasteMode(CourseTasteMode.valueOf(request.courseTasteMode()));
        return PreferenceResponse.from(userPreferenceRepository.save(preference));
    }

    /** 코스를 만들 때 취향을 반영할지의 기본값. 설정한 적 없으면 {@code TASTE}. */
    @Transactional(readOnly = true)
    public CourseTasteMode courseTasteModeOf(Long userId) {
        return find(userId).getCourseTasteMode();
    }

    private UserPreference find(Long userId) {
        return userPreferenceRepository.findById(userId).orElseGet(() -> UserPreference.defaultsFor(userId));
    }
}
