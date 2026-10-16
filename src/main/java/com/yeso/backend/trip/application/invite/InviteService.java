package com.yeso.backend.trip.application.invite;

import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.attraction.application.region.RegionQueryService;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.application.course.CourseService;
import com.yeso.backend.trip.application.course.CourseStorage;
import com.yeso.backend.trip.presentation.course.CourseResponse;
import com.yeso.backend.shared.token.InvalidExpiresInDaysException;
import com.yeso.backend.trip.domain.InviteExpiredException;
import com.yeso.backend.trip.domain.InviteNotFoundException;
import com.yeso.backend.trip.domain.InviteRevokedException;
import com.yeso.backend.shared.token.TokenAudience;
import com.yeso.backend.trip.domain.TripInvitation;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.shared.token.OpaqueTokenGenerator;
import com.yeso.backend.trip.infrastructure.TripFriendInvitationRepository;
import com.yeso.backend.trip.infrastructure.TripInvitationRepository;
import com.yeso.backend.trip.presentation.context.TripContextResponse;
import com.yeso.backend.trip.presentation.invite.CreateInviteRequest;
import com.yeso.backend.trip.presentation.invite.InvitePublicSummaryResponse;
import com.yeso.backend.trip.presentation.invite.InviteResponse;
import com.yeso.backend.trip.presentation.invite.LinkSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회원을 여행 참여자로 들이는 초대 링크(2026-09-24 정책). 참여자 누구나 발급·폐기하고, 받은 회원은
 * 로그인과 최초 설문을 마친 뒤 수락한다. 수락자의 기존 여행과 날짜가 겹치면 수락할 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InviteService {

    private static final int DEFAULT_EXPIRES_IN_DAYS = 7;
    private static final int MIN_EXPIRES_IN_DAYS = 1;
    private static final int MAX_EXPIRES_IN_DAYS = 30;

    private final TripInvitationRepository invitationRepository;
    private final TripFriendInvitationRepository friendInvitationRepository;
    private final UserRepository userRepository;
    private final TripService tripService;
    private final CourseService courseService;
    private final CourseStorage courseStorage;
    private final RegionQueryService regionQueryService;
    private final OpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    public InviteResponse createInvite(Long userId, Long tripId, CreateInviteRequest request) {
        TripPlan tripPlan = tripService.requireParticipantTrip(userId, tripId);
        tripService.requireInvitable(tripPlan);
        int expiresInDays = resolveExpiresInDays(request.expiresInDays());
        User inviter = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        String token = tokenGenerator.generate(TokenAudience.INVITE);
        TripInvitation invitation = new TripInvitation(
                tripPlan, tokenGenerator.hash(token), inviter, LocalDateTime.now(clock).plusDays(expiresInDays));
        invitationRepository.save(invitation);

        return InviteResponse.of(invitation, token);
    }

    @Transactional(readOnly = true)
    public List<LinkSummaryResponse> listInvites(Long userId, Long tripId) {
        tripService.requireParticipantTrip(userId, tripId);
        return invitationRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).stream()
                .map(LinkSummaryResponse::from)
                .toList();
    }

    public void revokeInvite(Long userId, Long tripId, Long inviteId) {
        tripService.requireParticipantTrip(userId, tripId);
        TripInvitation invitation = invitationRepository.findById(inviteId).orElseThrow(InviteNotFoundException::new);
        if (!invitation.getTripPlan().getId().equals(tripId)) {
            throw new InviteNotFoundException();
        }
        if (!invitation.isRevoked()) {
            invitation.revoke(LocalDateTime.now(clock));
        }
    }

    @Transactional(readOnly = true)
    public InvitePublicSummaryResponse publicSummary(String token) {
        TripInvitation invitation = requireActiveInvitation(token);
        TripPlan tripPlan = invitation.getTripPlan();
        Region region = tripPlan.getRegion();
        List<InvitePublicSummaryResponse.Participant> participants = tripService.participantNicknames(tripPlan.getId()).stream()
                .map(InvitePublicSummaryResponse.Participant::new)
                .toList();
        return new InvitePublicSummaryResponse(
                true,
                tripPlan.displayTitle(),
                tripPlan.getStartDate(),
                tripPlan.getEndDate(),
                region == null ? null : region.getSigCd(),
                region == null ? null : region.getProvince() + " " + region.getCity(),
                region == null ? null : regionQueryService.heroImageUrl(region.getSigCd()).orElse(null),
                invitation.getInvitedByUser().getNickname(),
                participants.size(),
                participants,
                courseStorage.hasCourse(tripPlan.getId()));
    }

    /**
     * 4-16 초대 링크로 코스 둘러보기(D9). 로그인 전에도 읽기 전용으로 본다. 모양은 4-13과 같고({@code myRole: VIEWER}),
     * 초대 미리보기처럼 참여자 이름을 보여 준다. 코스가 없으면 {@code days: []}다.
     */
    @Transactional(readOnly = true)
    public CourseResponse course(String token) {
        TripInvitation invitation = requireActiveInvitation(token);
        return courseService.inviteView(invitation.getTripPlan());
    }

    /** 이미 참여 중이면 {@code joined=false}로 같은 결과를 돌려준다(멱등). 컨트롤러가 201/200을 고른다. */
    public AcceptResult accept(Long userId, String token) {
        TripInvitation invitation = requireActiveInvitation(token);
        Long tripId = invitation.getTripPlan().getId();
        boolean joined = tripService.joinAsMember(userId, tripId, invitation.getId());
        // 링크로 먼저 참여하면 대기 중인 친구 초대는 수락된 것으로 보고 받은 초대함에서 뺀다(4-6).
        friendInvitationRepository.acceptPending(tripId, userId, LocalDateTime.now(clock));
        return new AcceptResult(joined, tripService.contextOf(invitation.getTripPlan()));
    }

    public record AcceptResult(boolean joined, TripContextResponse context) {
    }

    private TripInvitation requireActiveInvitation(String token) {
        tokenGenerator.requireAudience(token, TokenAudience.INVITE);
        TripInvitation invitation = invitationRepository.findByTokenHash(tokenGenerator.hash(token))
                .orElseThrow(InviteNotFoundException::new);
        if (invitation.isRevoked()) {
            throw new InviteRevokedException();
        }
        if (!invitation.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new InviteExpiredException();
        }
        return invitation;
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
