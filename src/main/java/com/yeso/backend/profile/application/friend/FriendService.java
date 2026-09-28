package com.yeso.backend.profile.application.friend;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.domain.FriendLink;
import com.yeso.backend.profile.domain.FriendLinkExpiredException;
import com.yeso.backend.profile.domain.FriendLinkNotFoundException;
import com.yeso.backend.profile.domain.FriendLinkRevokedException;
import com.yeso.backend.profile.domain.FriendLinkSelfException;
import com.yeso.backend.profile.domain.FriendNotFoundException;
import com.yeso.backend.profile.domain.Friendship;
import com.yeso.backend.profile.infrastructure.FriendLinkRepository;
import com.yeso.backend.profile.infrastructure.FriendshipRepository;
import com.yeso.backend.profile.presentation.friend.CreateFriendLinkRequest;
import com.yeso.backend.profile.presentation.friend.FriendLinkPreviewResponse;
import com.yeso.backend.profile.presentation.friend.FriendLinkResponse;
import com.yeso.backend.profile.presentation.friend.FriendLinkSummaryResponse;
import com.yeso.backend.profile.presentation.friend.FriendResponse;
import com.yeso.backend.shared.token.InvalidExpiresInDaysException;
import com.yeso.backend.shared.token.OpaqueTokenGenerator;
import com.yeso.backend.shared.token.TokenAudience;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 친구 초대 링크와 친구 관계(docs/api/profile.md 2-4~2-10). 링크를 만든 사람은 발급으로, 받은 사람은
 * 수락으로 동의하므로 수락하면 바로 친구다. 친구 요청·승인 단계는 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FriendService {

    private static final int DEFAULT_EXPIRES_IN_DAYS = 7;
    private static final int MIN_EXPIRES_IN_DAYS = 1;
    private static final int MAX_EXPIRES_IN_DAYS = 30;

    private final FriendLinkRepository friendLinkRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final OpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    public FriendLinkResponse createLink(Long userId, CreateFriendLinkRequest request) {
        int expiresInDays = resolveExpiresInDays(request == null ? null : request.expiresInDays());
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        String token = tokenGenerator.generate(TokenAudience.FRIEND_LINK);
        FriendLink link = friendLinkRepository.save(
                new FriendLink(user, tokenGenerator.hash(token), LocalDateTime.now(clock).plusDays(expiresInDays)));
        return FriendLinkResponse.of(link, token);
    }

    @Transactional(readOnly = true)
    public List<FriendLinkSummaryResponse> listLinks(Long userId) {
        return friendLinkRepository.findByCreatedByIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(FriendLinkSummaryResponse::from)
                .toList();
    }

    /** 이후 수락만 막는다. 이미 맺은 친구 관계는 그대로 둔다. */
    public void revokeLink(Long userId, Long linkId) {
        FriendLink link = friendLinkRepository.findById(linkId)
                .filter(found -> found.isCreatedBy(userId))
                .orElseThrow(FriendLinkNotFoundException::new);
        link.revoke(LocalDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public FriendLinkPreviewResponse preview(String token) {
        FriendLink link = requireActiveLink(token);
        return new FriendLinkPreviewResponse(true, link.getCreatedBy().getNickname());
    }

    /** 새로 친구가 되면 {@code created=true}, 이미 친구면 같은 관계를 {@code created=false}로 돌려준다(멱등). */
    public AcceptResult accept(Long userId, String token) {
        FriendLink link = requireActiveLink(token);
        if (link.isCreatedBy(userId)) {
            throw new FriendLinkSelfException();
        }
        Long inviterId = link.getCreatedBy().getId();
        long low = Math.min(userId, inviterId);
        long high = Math.max(userId, inviterId);
        boolean created = friendshipRepository.insertAcceptedIfAbsent(low, high, userId, LocalDateTime.now(clock)) == 1;
        if (created) {
            link.countAcceptance();
        }
        Friendship friendship = friendshipRepository.findAccepted(low, high)
                .orElseThrow(() -> new IllegalStateException("친구 관계를 만들지 못했습니다: " + low + "-" + high));
        return new AcceptResult(created, FriendResponse.of(friendship, userId));
    }

    public record AcceptResult(boolean created, FriendResponse friend) {
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> listFriends(Long userId) {
        return friendshipRepository.findAcceptedOf(userId).stream()
                .map(friendship -> FriendResponse.of(friendship, userId))
                .toList();
    }

    public void unfriend(Long userId, Long friendUserId) {
        int deleted = friendshipRepository.deleteAccepted(
                Math.min(userId, friendUserId), Math.max(userId, friendUserId));
        if (deleted == 0) {
            throw new FriendNotFoundException(friendUserId);
        }
    }

    private FriendLink requireActiveLink(String token) {
        tokenGenerator.requireAudience(token, TokenAudience.FRIEND_LINK);
        FriendLink link = friendLinkRepository.findByTokenHash(tokenGenerator.hash(token))
                .orElseThrow(FriendLinkNotFoundException::new);
        if (link.isRevoked()) {
            throw new FriendLinkRevokedException();
        }
        if (link.isExpired(LocalDateTime.now(clock))) {
            throw new FriendLinkExpiredException();
        }
        return link;
    }

    private static int resolveExpiresInDays(Integer requested) {
        if (requested == null) {
            return DEFAULT_EXPIRES_IN_DAYS;
        }
        if (requested < MIN_EXPIRES_IN_DAYS || requested > MAX_EXPIRES_IN_DAYS) {
            throw new InvalidExpiresInDaysException();
        }
        return requested;
    }
}
