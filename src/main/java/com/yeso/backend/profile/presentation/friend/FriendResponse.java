package com.yeso.backend.profile.presentation.friend;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.profile.domain.Friendship;

import java.time.LocalDateTime;

/** 친구 한 명. {@code since}는 친구가 된 시각이다. */
public record FriendResponse(Long userId, String nickname, LocalDateTime since) {

    public static FriendResponse of(Friendship friendship, Long viewerId) {
        User friend = friendship.otherThan(viewerId);
        return new FriendResponse(friend.getId(), friend.getNickname(), friendship.getCreatedAt());
    }
}
