package com.yeso.backend.trip.presentation.invite;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "4. 초대·공유", description = "docs/api/trip.md §4")
@RestController
@RequiredArgsConstructor
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    @Operation(summary = "4-9 공유 링크 발급")
    @PostMapping("/api/courses/{tripId}/share-links")
    public ResponseEntity<ShareLinkResponse> create(
            @CurrentUserId Long userId, @PathVariable Long tripId, @Valid @RequestBody CreateShareLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shareLinkService.create(userId, tripId, request));
    }

    @Operation(summary = "4-10 공유 링크 목록")
    @GetMapping("/api/courses/{tripId}/share-links")
    public List<LinkSummaryResponse> list(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return shareLinkService.list(userId, tripId);
    }

    @Operation(summary = "4-11 공유 링크 폐기")
    @DeleteMapping("/api/courses/{tripId}/share-links/{linkId}")
    public ResponseEntity<Void> revoke(
            @CurrentUserId Long userId, @PathVariable Long tripId, @PathVariable Long linkId) {
        shareLinkService.revoke(userId, tripId, linkId);
        return ResponseEntity.noContent().build();
    }
}
