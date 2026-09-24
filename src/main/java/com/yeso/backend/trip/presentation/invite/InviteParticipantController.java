package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.application.invite.InviteService;
import com.yeso.backend.onboarding.presentation.OnboardingSubmissionRequest;
import com.yeso.backend.onboarding.presentation.OnboardingSubmissionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InviteParticipantController {

    private final InviteService inviteService;

    @PostMapping("/api/invite-participants/{id}/onboarding")
    public ResponseEntity<OnboardingSubmissionResponse> onboarding(
            @CurrentGuestParticipant Long guestParticipantId,
            @PathVariable Long id,
            @Valid @RequestBody OnboardingSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inviteService.submitGuestOnboarding(guestParticipantId, id, request));
    }
}
