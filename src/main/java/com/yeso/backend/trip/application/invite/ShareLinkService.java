package com.yeso.backend.trip.application.invite;

import com.yeso.backend.trip.domain.CourseShareLink;
import com.yeso.backend.trip.domain.InsufficientSharePermissionException;
import com.yeso.backend.trip.domain.InvalidExpiresInDaysException;
import com.yeso.backend.trip.domain.ShareLinkExpiredException;
import com.yeso.backend.trip.domain.ShareLinkNotFoundException;
import com.yeso.backend.trip.domain.ShareLinkRevokedException;
import com.yeso.backend.trip.domain.SharePermission;
import com.yeso.backend.trip.domain.ShareSession;
import com.yeso.backend.trip.domain.ShareSessionInvalidException;
import com.yeso.backend.trip.domain.TokenAudience;
import com.yeso.backend.trip.infrastructure.CourseShareLinkRepository;
import com.yeso.backend.trip.infrastructure.OpaqueTokenGenerator;
import com.yeso.backend.trip.infrastructure.ShareSessionRepository;
import com.yeso.backend.trip.presentation.invite.CreateShareLinkRequest;
import com.yeso.backend.trip.presentation.invite.PatchShareLinkRequest;
import com.yeso.backend.trip.presentation.invite.ShareLinkResponse;
import com.yeso.backend.trip.presentation.invite.ShareLinkSummaryResponse;
import com.yeso.backend.trip.presentation.invite.SharedCourseViewResponse;
import com.yeso.backend.trip.application.TripService;
import com.yeso.backend.trip.domain.TripPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final TripService tripService;
    private final OpaqueTokenGenerator tokenGenerator;

    public ShareLinkResponse create(Long ownerId, Long tripId, CreateShareLinkRequest request) {
        TripPlan tripPlan = tripService.requireOwnedTrip(ownerId, tripId);
        SharePermission permission = SharePermission.parse(request.permission());
        int expiresInDays = resolveExpiresInDays(request.expiresInDays());

        String token = tokenGenerator.generate(TokenAudience.SHARE_LINK);
        CourseShareLink link = new CourseShareLink(
                tripPlan, tokenGenerator.hash(token), permission,
                LocalDateTime.now().plusDays(expiresInDays), tripPlan.getOwnerUser());
        shareLinkRepository.save(link);

        return ShareLinkResponse.of(link, token);
    }

    @Transactional(readOnly = true)
    public List<ShareLinkSummaryResponse> list(Long ownerId, Long tripId) {
        tripService.requireOwnedTrip(ownerId, tripId);
        return shareLinkRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).stream()
                .map(ShareLinkSummaryResponse::from)
                .toList();
    }

    public ShareLinkSummaryResponse patch(Long ownerId, Long tripId, Long linkId, PatchShareLinkRequest request) {
        tripService.requireOwnedTrip(ownerId, tripId);
        CourseShareLink link = requireLinkOfTrip(tripId, linkId);
        if (request.permission() != null) {
            link.changePermission(SharePermission.parse(request.permission()));
        }
        if (Boolean.TRUE.equals(request.revoked())) {
            link.revoke();
        }
        return ShareLinkSummaryResponse.from(link);
    }

    /** @return 발급한 share session opaque token(원문). 컨트롤러가 cookie로 감싼다. */
    public String openAndIssueSession(String token) {
        CourseShareLink link = requireActiveLink(token);
        String sessionToken = tokenGenerator.generate(TokenAudience.SHARE_SESSION);
        shareSessionRepository.save(new ShareSession(
                link, tokenGenerator.hash(sessionToken), LocalDateTime.now().plusHours(2)));
        return sessionToken;
    }

    @Transactional(readOnly = true)
    public SharedCourseViewResponse view(String shareSessionToken) {
        CourseShareLink link = requireActiveSessionLink(shareSessionToken);
        TripPlan tripPlan = link.getTripPlan();
        return new SharedCourseViewResponse(
                tripPlan.getId(), link.getPermission().name(), tripPlan.getStatus().name(),
                tripPlan.getStartDate(), tripPlan.getEndDate(), List.of());
    }

    /**
     * 공유 세션으로 편집을 시도할 때 쓰는 공개 계약이다. 장소·순서·식당 수정은 WORK-07/08이
     * 구현하므로 여기서는 "이 세션이 그 수정을 해도 되는가"만 판정해 대상 trip을 돌려준다.
     * 날짜·소유자·참여자·공유 권한 변경은 EDIT 세션으로도 불가하며 owner 전용 API만 다룬다.
     */
    @Transactional(readOnly = true)
    public TripPlan requireEditableSession(String shareSessionToken) {
        CourseShareLink link = requireActiveSessionLink(shareSessionToken);
        if (!link.getPermission().allowsEdit()) {
            throw new InsufficientSharePermissionException();
        }
        return link.getTripPlan();
    }

    /** 세션 자체의 유효성과 뒤에 있는 링크의 폐기·만료를 함께 본다 — 링크를 끊으면 세션도 죽는다. */
    private CourseShareLink requireActiveSessionLink(String shareSessionToken) {
        if (shareSessionToken == null || shareSessionToken.isBlank()) {
            throw new ShareSessionInvalidException();
        }
        tokenGenerator.requireAudience(shareSessionToken, TokenAudience.SHARE_SESSION);
        ShareSession session = shareSessionRepository.findBySessionTokenHash(tokenGenerator.hash(shareSessionToken))
                .orElseThrow(ShareSessionInvalidException::new);
        if (!session.isActive(LocalDateTime.now())) {
            throw new ShareSessionInvalidException();
        }
        CourseShareLink link = session.getShareLink();
        if (link.isRevoked()) {
            throw new ShareLinkRevokedException();
        }
        if (!link.isActive(LocalDateTime.now())) {
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
        if (!link.isActive(LocalDateTime.now())) {
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
