package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.invite.InviteService;
import com.yeso.backend.trip.presentation.TripContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;

    @PostMapping("/api/trips/{tripId}/invites")
    public ResponseEntity<InviteResponse> create(
            @CurrentUserId Long userId, @PathVariable Long tripId, @RequestBody CreateInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inviteService.createInvite(userId, tripId, request));
    }

    @GetMapping("/api/trips/{tripId}/invites")
    public List<LinkSummaryResponse> list(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return inviteService.listInvites(userId, tripId);
    }

    @DeleteMapping("/api/trips/{tripId}/invites/{inviteId}")
    public ResponseEntity<Void> revoke(
            @CurrentUserId Long userId, @PathVariable Long tripId, @PathVariable Long inviteId) {
        inviteService.revokeInvite(userId, tripId, inviteId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/invites/{token}")
    public InvitePublicSummaryResponse publicSummary(@PathVariable String token) {
        return inviteService.publicSummary(token);
    }

    /** 새로 참여하면 201, 이미 참여 중이면 같은 여행을 200으로 돌려준다. */
    @PostMapping("/api/invites/{token}/accept")
    public ResponseEntity<TripContextResponse> accept(@CurrentUserId Long userId, @PathVariable String token) {
        InviteService.AcceptResult result = inviteService.accept(userId, token);
        return ResponseEntity.status(result.joined() ? HttpStatus.CREATED : HttpStatus.OK).body(result.context());
    }
}
