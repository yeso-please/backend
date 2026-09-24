package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.application.invite.InviteService;
import com.yeso.backend.shared.web.CurrentUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;

    @PostMapping("/api/trips/{tripId}/invites")
    public ResponseEntity<InviteResponse> create(
            @CurrentUserId Long ownerId, @PathVariable Long tripId, @RequestBody CreateInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inviteService.createInvite(ownerId, tripId, request));
    }

    @GetMapping("/api/trips/{tripId}/invites")
    public List<InviteSummaryResponse> list(@CurrentUserId Long ownerId, @PathVariable Long tripId) {
        return inviteService.listInvites(ownerId, tripId);
    }

    @GetMapping("/api/trips/{tripId}/invites/{inviteId}")
    public InviteSummaryResponse get(
            @CurrentUserId Long ownerId, @PathVariable Long tripId, @PathVariable Long inviteId) {
        return inviteService.getInvite(ownerId, tripId, inviteId);
    }

    @PatchMapping("/api/trips/{tripId}/invites/{inviteId}")
    public InviteSummaryResponse patch(
            @CurrentUserId Long ownerId, @PathVariable Long tripId, @PathVariable Long inviteId,
            @RequestBody PatchInviteRequest request) {
        return inviteService.patchInvite(ownerId, tripId, inviteId, request);
    }

    @GetMapping("/api/invites/{token}")
    public InvitePublicSummaryResponse publicSummary(@PathVariable String token) {
        return inviteService.publicSummary(token);
    }

    @PostMapping("/api/invites/{token}/participants")
    public ResponseEntity<JoinInviteResponse> join(
            @PathVariable String token, @RequestBody JoinInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inviteService.join(token, request));
    }
}
