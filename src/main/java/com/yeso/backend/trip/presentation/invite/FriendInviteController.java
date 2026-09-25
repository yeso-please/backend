package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.invite.FriendInviteService;
import com.yeso.backend.trip.presentation.context.TripContextResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@Tag(name = "4. 초대·공유", description = "docs/api/trip.md §4")
@RestController
@RequiredArgsConstructor
public class FriendInviteController {

    private final FriendInviteService friendInviteService;

    /** 새 초대면 201, 대기 중인 초대가 이미 있으면 그 초대를 200으로 돌려준다. */
    @Operation(summary = "4-6 친구 초대")
    @PostMapping("/api/trips/{tripId}/friend-invites")
    public ResponseEntity<FriendInviteResponse> invite(
            @CurrentUserId Long userId, @PathVariable Long tripId,
            @Valid @RequestBody CreateFriendInviteRequest request) {
        FriendInviteService.InviteResult result = friendInviteService.invite(userId, tripId, request.friendUserId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.invite());
    }

    @Operation(summary = "4-7 받은 초대 목록")
    @GetMapping("/api/me/trip-invites")
    public List<ReceivedTripInviteResponse> listReceived(@CurrentUserId Long userId) {
        return friendInviteService.listReceived(userId);
    }

    @Operation(summary = "4-8 받은 초대 수락·거절")
    @PostMapping("/api/me/trip-invites/{id}/accept")
    public ResponseEntity<TripContextResponse> accept(@CurrentUserId Long userId, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(friendInviteService.accept(userId, id));
    }

    @Operation(summary = "4-8 받은 초대 수락·거절")
    @PostMapping("/api/me/trip-invites/{id}/decline")
    public ResponseEntity<Void> decline(@CurrentUserId Long userId, @PathVariable Long id) {
        friendInviteService.decline(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "4-14 보낸 친구 초대 목록")
    @GetMapping("/api/trips/{tripId}/friend-invites")
    public List<SentFriendInviteResponse> listSent(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return friendInviteService.listSent(userId, tripId);
    }

    @Operation(summary = "4-15 친구 초대 취소")
    @DeleteMapping("/api/trips/{tripId}/friend-invites/{inviteId}")
    public ResponseEntity<Void> cancel(
            @CurrentUserId Long userId, @PathVariable Long tripId, @PathVariable Long inviteId) {
        friendInviteService.cancel(userId, tripId, inviteId);
        return ResponseEntity.noContent().build();
    }
}
