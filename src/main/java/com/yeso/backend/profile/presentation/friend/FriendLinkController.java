package com.yeso.backend.profile.presentation.friend;

import com.yeso.backend.profile.application.friend.FriendService;
import com.yeso.backend.shared.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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

@Tag(name = "2. 온보딩·친구", description = "docs/api/profile.md §2")
@RestController
@RequiredArgsConstructor
public class FriendLinkController {

    private final FriendService friendService;

    @Operation(summary = "2-4 친구 초대 링크 발급")
    @PostMapping("/api/friend-links")
    public ResponseEntity<FriendLinkResponse> create(
            @CurrentUserId Long userId, @Valid @RequestBody(required = false) CreateFriendLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(friendService.createLink(userId, request));
    }

    @Operation(summary = "2-5 내 친구 초대 링크 목록")
    @GetMapping("/api/friend-links")
    public List<FriendLinkSummaryResponse> list(@CurrentUserId Long userId) {
        return friendService.listLinks(userId);
    }

    @Operation(summary = "2-6 친구 초대 링크 폐기")
    @DeleteMapping("/api/friend-links/{id}")
    public ResponseEntity<Void> revoke(@CurrentUserId Long userId, @PathVariable Long id) {
        friendService.revokeLink(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "2-7 친구 초대 링크 미리보기")
    @SecurityRequirements()
    @GetMapping("/api/friend-links/by-token/{token}")
    public FriendLinkPreviewResponse preview(@PathVariable String token) {
        return friendService.preview(token);
    }

    /** 새로 친구가 되면 201, 이미 친구면 같은 관계를 200으로 돌려준다. */
    @Operation(summary = "2-8 친구 초대 수락")
    @PostMapping("/api/friend-links/by-token/{token}/accept")
    public ResponseEntity<FriendResponse> accept(@CurrentUserId Long userId, @PathVariable String token) {
        FriendService.AcceptResult result = friendService.accept(userId, token);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.friend());
    }
}
