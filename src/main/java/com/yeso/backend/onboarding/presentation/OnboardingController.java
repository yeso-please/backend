package com.yeso.backend.onboarding.presentation;

import com.yeso.backend.onboarding.application.OnboardingService;
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

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/questions")
    public OnboardingQuestionsResponse questions() {
        return onboardingService.getQuestions();
    }

    @PostMapping("/submissions")
    public ResponseEntity<OnboardingSubmissionResponse> submit(
            @CurrentUserId Long userId, @Valid @RequestBody OnboardingSubmissionRequest request) {
        UUID submissionId = onboardingService.submit(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(onboardingService.getSubmissionResponse(submissionId));
    }

    @GetMapping("/me")
    public OnboardingMeResponse me(@CurrentUserId Long userId) {
        return onboardingService.getMe(userId);
    }
}
