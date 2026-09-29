package com.yeso.backend.trip.presentation.diary;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.diary.DiaryQueryService;
import com.yeso.backend.trip.infrastructure.DiaryShareSessionCookieFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@Tag(name = "6. 여행기·사진 지도", description = "docs/api/trip.md §6")
@RestController
@RequiredArgsConstructor
public class DiaryQueryController {
    private final DiaryQueryService service;
    private final DiaryShareSessionCookieFactory cookieFactory;

    @Operation(summary = "6-7 내 여행 지도")
    @GetMapping("/api/me/travel-map")
    public List<DiaryMapPinResponse> myMap(@CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.myMap(userId, from, to);
    }

    @Operation(summary = "6-8 친구 여행 지도")
    @GetMapping("/api/friends/{userId}/travel-map")
    public List<DiaryMapPinResponse> friendMap(@CurrentUserId Long viewerId, @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.friendMap(viewerId, userId, from, to);
    }

    @Operation(summary = "6-9 여행기 공유 링크 발급")
    @PostMapping("/api/diaries/{diaryId}/share-links")
    public ResponseEntity<DiaryShareLinkResponse> createLink(@CurrentUserId Long userId, @PathVariable Long diaryId,
            @RequestBody(required = false) DiaryShareLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLink(userId, diaryId, request));
    }

    @Operation(summary = "6-9 여행기 공유 링크 목록")
    @GetMapping("/api/diaries/{diaryId}/share-links")
    public List<DiaryShareLinkResponse> listLinks(@CurrentUserId Long userId, @PathVariable Long diaryId) {
        return service.listLinks(userId, diaryId);
    }

    @Operation(summary = "6-10 여행기 공유 링크 폐기")
    @DeleteMapping("/api/diaries/{diaryId}/share-links/{linkId}")
    public ResponseEntity<Void> revoke(@CurrentUserId Long userId, @PathVariable Long diaryId, @PathVariable Long linkId) {
        service.revokeLink(userId, diaryId, linkId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "6-11 여행기 공유 링크 열기")
    @SecurityRequirements()
    @GetMapping("/api/shared/diaries/{token}")
    public ResponseEntity<Void> open(@PathVariable String token) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.SET_COOKIE, cookieFactory.create(service.open(token)).toString())
                .location(URI.create("/api/shared/diaries")).build();
    }

    @Operation(summary = "6-12 공유 여행기 조회")
    @SecurityRequirements()
    @GetMapping("/api/shared/diaries")
    public DiaryResponse shared(@CookieValue(value = DiaryShareSessionCookieFactory.COOKIE_NAME, required = false) String cookie) {
        return service.viewShared(cookie);
    }
}
