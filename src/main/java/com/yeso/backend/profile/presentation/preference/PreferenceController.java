package com.yeso.backend.profile.presentation.preference;

import com.yeso.backend.profile.application.preference.PreferenceService;
import com.yeso.backend.shared.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "2. 온보딩·친구", description = "docs/api/profile.md §2")
@RestController
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;

    @Operation(summary = "2-11 내 설정")
    @GetMapping("/api/me/preferences")
    public PreferenceResponse get(@CurrentUserId Long userId) {
        return preferenceService.get(userId);
    }

    @Operation(summary = "2-12 설정 바꾸기")
    @PatchMapping("/api/me/preferences")
    public PreferenceResponse update(@CurrentUserId Long userId, @Valid @RequestBody UpdatePreferenceRequest request) {
        return preferenceService.update(userId, request);
    }
}
