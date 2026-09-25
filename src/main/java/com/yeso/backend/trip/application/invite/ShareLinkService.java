package com.yeso.backend.trip.application.invite;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.trip.domain.CourseShareLink;
import com.yeso.backend.shared.token.InvalidExpiresInDaysException;
import com.yeso.backend.trip.domain.ShareLinkExpiredException;
import com.yeso.backend.trip.domain.ShareLinkNotFoundException;
import com.yeso.backend.trip.domain.ShareLinkRevokedException;
import com.yeso.backend.trip.domain.ShareSession;
import com.yeso.backend.trip.domain.ShareSessionInvalidException;
import com.yeso.backend.shared.token.TokenAudience;
import com.yeso.backend.trip.infrastructure.CourseShareLinkRepository;
import com.yeso.backend.shared.token.OpaqueTokenGenerator;
import com.yeso.backend.trip.infrastructure.ShareSessionRepository;
import com.yeso.backend.trip.presentation.invite.CreateShareLinkRequest;
import com.yeso.backend.trip.presentation.invite.ShareLinkResponse;
import com.yeso.backend.trip.presentation.invite.LinkSummaryResponse;
import com.yeso.backend.trip.presentation.invite.SharedCourseViewResponse;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.TripPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ShareLinkService {

    private static final int DEFAULT_EXPIRES_IN_DAYS = 7;
    private static final int MIN_EXPIRES_IN_DAYS = 1;
    private static final int MAX_EXPIRES_IN_DAYS = 30;

    private final CourseShareLinkRepository shareLinkRepository;
    private final ShareSessionRepository shareSessionRepository;
    private final UserRepository userRepository;
    private final TripService tripService;
    private final OpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    public ShareLinkResponse create(Long userId, Long tripId, CreateShareLinkRequest request) {
        TripPlan tripPlan = tripService.requireParticipantTrip(userId, tripId);
        int expiresInDays = resolveExpiresInDays(request.expiresInDays());
        User creator = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        String token = tokenGenerator.generate(TokenAudience.SHARE_LINK);
        CourseShareLink link = new CourseShareLink(
                tripPlan, tokenGenerator.hash(token), LocalDateTime.now(clock).plusDays(expiresInDays), creator);
        shareLinkRepository.save(link);

        return ShareLinkResponse.of(link, token);
    }

    @Transactional(readOnly = true)
    public List<LinkSummaryResponse> list(Long userId, Long tripId) {
        tripService.requireParticipantTrip(userId, tripId);
        return shareLinkRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).stream()
                .map(LinkSummaryResponse::from)
                .toList();
    }

    /** 폐기하면 이미 발급된 share session도 다음 요청부터 무효다. */
    public void revoke(Long userId, Long tripId, Long linkId) {
        tripService.requireParticipantTrip(userId, tripId);
        CourseShareLink link = requireLinkOfTrip(tripId, linkId);
        if (!link.isRevoked()) {
            link.revoke(LocalDateTime.now(clock));
        }
    }

    /** @return 발급한 share session opaque token(원문). 컨트롤러가 cookie로 감싼다. */
    public String openAndIssueSession(String token) {
        CourseShareLink link = requireActiveLink(token);
        String sessionToken = tokenGenerator.generate(TokenAudience.SHARE_SESSION);
        shareSessionRepository.save(new ShareSession(
                link, tokenGenerator.hash(sessionToken), LocalDateTime.now(clock).plusHours(2)));
        return sessionToken;
    }

    @Transactional(readOnly = true)
    public SharedCourseViewResponse view(String shareSessionToken) {
        CourseShareLink link = requireActiveSessionLink(shareSessionToken);
        TripPlan tripPlan = link.getTripPlan();
        return new SharedCourseViewResponse(
                tripPlan.getId(), SharedCourseViewResponse.VIEWER,
                tripPlan.getStartDate(), tripPlan.getEndDate(), List.of());
    }

    /** 세션 자체의 유효성과 뒤에 있는 링크의 폐기·만료를 함께 본다 — 링크를 끊으면 세션도 죽는다. */
    private CourseShareLink requireActiveSessionLink(String shareSessionToken) {
        if (shareSessionToken == null || shareSessionToken.isBlank()) {
            throw new ShareSessionInvalidException();
        }
        tokenGenerator.requireAudience(shareSessionToken, TokenAudience.SHARE_SESSION);
        ShareSession session = shareSessionRepository.findBySessionTokenHash(tokenGenerator.hash(shareSessionToken))
                .orElseThrow(ShareSessionInvalidException::new);
        if (!session.isActive(LocalDateTime.now(clock))) {
            throw new ShareSessionInvalidException();
        }
        CourseShareLink link = session.getShareLink();
        if (link.isRevoked()) {
            throw new ShareLinkRevokedException();
        }
        if (!link.isActive(LocalDateTime.now(clock))) {
            throw new ShareLinkExpiredException();
        }
        return link;
    }

    private CourseShareLink requireActiveLink(String token) {
        tokenGenerator.requireAudience(token, TokenAudience.SHARE_LINK);
        CourseShareLink link = shareLinkRepository.findByTokenHash(tokenGenerator.hash(token))
                .orElseThrow(ShareLinkNotFoundException::new);
        if (link.isRevoked()) {
            throw new ShareLinkRevokedException();
        }
        if (!link.isActive(LocalDateTime.now(clock))) {
            throw new ShareLinkExpiredException();
        }
        return link;
    }

    private CourseShareLink requireLinkOfTrip(Long tripId, Long linkId) {
        CourseShareLink link = shareLinkRepository.findById(linkId).orElseThrow(ShareLinkNotFoundException::new);
        if (!link.getTripPlan().getId().equals(tripId)) {
            throw new ShareLinkNotFoundException();
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
