package com.yeso.backend.profile.presentation.friend;

import com.yeso.backend.profile.application.friend.FriendService;
import com.yeso.backend.shared.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "2. 온보딩·친구", description = "docs/api/profile.md §2")
@RestController
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @Operation(summary = "2-9 친구 목록")
    @GetMapping("/api/friends")
    public List<FriendResponse> list(@CurrentUserId Long userId) {
        return friendService.listFriends(userId);
    }

    @Operation(summary = "2-10 친구 끊기")
    @DeleteMapping("/api/friends/{userId}")
    public ResponseEntity<Void> unfriend(@CurrentUserId Long currentUserId, @PathVariable Long userId) {
        friendService.unfriend(currentUserId, userId);
        return ResponseEntity.noContent().build();
    }
}
