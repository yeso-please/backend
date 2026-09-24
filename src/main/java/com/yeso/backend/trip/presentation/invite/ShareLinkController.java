package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.trip.application.invite.ShareLinkService;
import com.yeso.backend.shared.web.CurrentUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "course"라는 별도 리소스가 아직 없어(WORK-06/07/08 이전) {id}는 tripId를 그대로 쓴다
 * (docs/features/trip-invitation.md 참고).
 */
@RestController
@RequiredArgsConstructor
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    @PostMapping("/api/courses/{tripId}/share-links")
    public ResponseEntity<ShareLinkResponse> create(
            @CurrentUserId Long ownerId, @PathVariable Long tripId, @RequestBody CreateShareLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shareLinkService.create(ownerId, tripId, request));
    }

    @GetMapping("/api/courses/{tripId}/share-links")
    public List<ShareLinkSummaryResponse> list(@CurrentUserId Long ownerId, @PathVariable Long tripId) {
        return shareLinkService.list(ownerId, tripId);
    }

    @PatchMapping("/api/courses/{tripId}/share-links/{linkId}")
    public ShareLinkSummaryResponse patch(
            @CurrentUserId Long ownerId, @PathVariable Long tripId, @PathVariable Long linkId,
            @RequestBody PatchShareLinkRequest request) {
        return shareLinkService.patch(ownerId, tripId, linkId, request);
    }
}
