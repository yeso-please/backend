package com.yeso.backend.profile.application.friend;

import com.yeso.backend.profile.infrastructure.FriendshipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 모듈이 쓰는 친구 관계 조회 계약. trip의 친구 여행 초대(4-6)가 친구 여부를 여기로 확인한다.
 * profile의 Repository를 직접 쓰지 않는다(docs/conventions/모듈-의존성.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendQueryService {

    private final FriendshipRepository friendshipRepository;

    /** 두 회원이 서로 친구면 true. 같은 사람이면 false. */
    public boolean areFriends(Long userId, Long otherUserId) {
        if (userId.equals(otherUserId)) {
            return false;
        }
        return friendshipRepository
                .findAccepted(Math.min(userId, otherUserId), Math.max(userId, otherUserId))
                .isPresent();
    }
}
