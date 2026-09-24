package com.yeso.backend.trip.presentation.invite;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.invite.ShareLinkService;
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

/** 코스는 여행의 일정이므로 {tripId}가 곧 course id다(docs/api/trip.md). */
@RestController
@RequiredArgsConstructor
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    @PostMapping("/api/courses/{tripId}/share-links")
    public ResponseEntity<ShareLinkResponse> create(
            @CurrentUserId Long userId, @PathVariable Long tripId, @RequestBody CreateShareLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shareLinkService.create(userId, tripId, request));
    }

    @GetMapping("/api/courses/{tripId}/share-links")
    public List<LinkSummaryResponse> list(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return shareLinkService.list(userId, tripId);
    }

    @DeleteMapping("/api/courses/{tripId}/share-links/{linkId}")
    public ResponseEntity<Void> revoke(
            @CurrentUserId Long userId, @PathVariable Long tripId, @PathVariable Long linkId) {
        shareLinkService.revoke(userId, tripId, linkId);
        return ResponseEntity.noContent().build();
    }
}
