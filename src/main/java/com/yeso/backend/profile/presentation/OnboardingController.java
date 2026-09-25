package com.yeso.backend.profile.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.yeso.backend.profile.application.OnboardingService;
import com.yeso.backend.shared.web.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "2. 온보딩·친구", description = "docs/api/profile.md §2")
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @Operation(summary = "2-1 온보딩 질문")
    @SecurityRequirements()
    @GetMapping("/questions")
    public OnboardingQuestionsResponse questions() {
        return onboardingService.getQuestions();
    }

    @Operation(summary = "2-2 온보딩 제출")
    @PostMapping("/submissions")
    public ResponseEntity<OnboardingSubmissionResponse> submit(
            @CurrentUserId Long userId, @Valid @RequestBody OnboardingSubmissionRequest request) {
        UUID submissionId = onboardingService.submit(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(onboardingService.getSubmissionResponse(submissionId));
    }

    @Operation(summary = "2-3 내 온보딩 결과")
    @GetMapping("/me")
    public OnboardingMeResponse me(@CurrentUserId Long userId) {
        return onboardingService.getMe(userId);
    }
}
