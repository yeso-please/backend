package com.yeso.backend.trip.application.invite;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.trip.domain.GuestSession;
import com.yeso.backend.trip.domain.GuestSessionInvalidException;
import com.yeso.backend.trip.domain.InvalidDisplayNameException;
import com.yeso.backend.trip.domain.InvalidExpiresInDaysException;
import com.yeso.backend.trip.domain.InviteExpiredException;
import com.yeso.backend.trip.domain.InviteNotFoundException;
import com.yeso.backend.trip.domain.InviteRevokedException;
import com.yeso.backend.trip.domain.SharePermission;
import com.yeso.backend.trip.domain.TokenAudience;
import com.yeso.backend.trip.domain.TripInvitation;
import com.yeso.backend.trip.infrastructure.GuestSessionRepository;
import com.yeso.backend.trip.infrastructure.OpaqueTokenGenerator;
import com.yeso.backend.trip.infrastructure.TripInvitationRepository;
import com.yeso.backend.trip.presentation.invite.CreateInviteRequest;
import com.yeso.backend.trip.presentation.invite.InvitePublicSummaryResponse;
import com.yeso.backend.trip.presentation.invite.InviteResponse;
import com.yeso.backend.trip.presentation.invite.InviteSummaryResponse;
import com.yeso.backend.trip.presentation.invite.JoinInviteRequest;
import com.yeso.backend.trip.presentation.invite.JoinInviteResponse;
import com.yeso.backend.trip.presentation.invite.PatchInviteRequest;
import com.yeso.backend.onboarding.application.OnboardingService;
import com.yeso.backend.onboarding.presentation.OnboardingSubmissionRequest;
import com.yeso.backend.onboarding.presentation.OnboardingSubmissionResponse;
import com.yeso.backend.trip.application.TripService;
import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class InviteService {

    private static final int DEFAULT_EXPIRES_IN_DAYS = 7;
    private static final int MIN_EXPIRES_IN_DAYS = 1;
    private static final int MAX_EXPIRES_IN_DAYS = 30;
    private static final int GUEST_SESSION_TTL_DAYS = 30;

    private final TripInvitationRepository invitationRepository;
    private final TripParticipantRepository participantRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final TripService tripService;
    private final OnboardingService onboardingService;
    private final OpaqueTokenGenerator tokenGenerator;

    public InviteResponse createInvite(Long ownerId, Long tripId, CreateInviteRequest request) {
        TripPlan tripPlan = tripService.requireOwnedTrip(ownerId, tripId);
        int expiresInDays = resolveExpiresInDays(request.expiresInDays());
        SharePermission permission = SharePermission.parseOrDefault(request.permission(), SharePermission.VIEW);

        String token = tokenGenerator.generate(TokenAudience.INVITE);
        User owner = tripPlan.getOwnerUser();
        TripInvitation invitation = new TripInvitation(
                tripPlan, tokenGenerator.hash(token), owner, permission,
                LocalDateTime.now().plusDays(expiresInDays));
        invitationRepository.save(invitation);

        return InviteResponse.of(invitation, token);
    }

    @Transactional(readOnly = true)
    public List<InviteSummaryResponse> listInvites(Long ownerId, Long tripId) {
        tripService.requireOwnedTrip(ownerId, tripId);
        return invitationRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).stream()
                .map(InviteSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public InviteSummaryResponse getInvite(Long ownerId, Long tripId, Long inviteId) {
        tripService.requireOwnedTrip(ownerId, tripId);
        return InviteSummaryResponse.from(requireInviteOfTrip(tripId, inviteId));
    }

    public InviteSummaryResponse patchInvite(Long ownerId, Long tripId, Long inviteId, PatchInviteRequest request) {
        tripService.requireOwnedTrip(ownerId, tripId);
        TripInvitation invitation = requireInviteOfTrip(tripId, inviteId);
        if (request.permission() != null) {
            invitation.changePermission(SharePermission.parse(request.permission()));
        }
        if (Boolean.TRUE.equals(request.revoked())) {
            invitation.revoke();
        }
        return InviteSummaryResponse.from(invitation);
    }

    @Transactional(readOnly = true)
    public InvitePublicSummaryResponse publicSummary(String token) {
        TripInvitation invitation = requireActiveInvitation(token);
        TripPlan tripPlan = invitation.getTripPlan();
        return new InvitePublicSummaryResponse(
                true, tripPlan.getStartDate(), tripPlan.getEndDate(), tripPlan.getOwnerUser().getNickname());
    }

    public JoinInviteResponse join(String token, JoinInviteRequest request) {
        TripInvitation invitation = requireActiveInvitation(token);
        String displayName = validateDisplayName(request.displayName());

        TripParticipant participant = TripParticipant.guest(invitation.getTripPlan(), invitation.getId(), displayName);
        participantRepository.save(participant);

        String sessionToken = tokenGenerator.generate(TokenAudience.GUEST_SESSION);
        guestSessionRepository.save(new GuestSession(
                participant, tokenGenerator.hash(sessionToken), LocalDateTime.now().plusDays(GUEST_SESSION_TTL_DAYS)));

        return new JoinInviteResponse(participant.getId(), sessionToken, displayName, participant.getStatus().name());
    }

    /** guest session의 참여자 본인만 자신의 온보딩을 제출할 수 있다. */
    public OnboardingSubmissionResponse submitGuestOnboarding(
            Long guestSessionParticipantId, Long pathParticipantId, OnboardingSubmissionRequest request) {
        if (!guestSessionParticipantId.equals(pathParticipantId)) {
            throw new GuestSessionInvalidException();
        }
        TripParticipant participant = participantRepository.findById(pathParticipantId)
                .orElseThrow(GuestSessionInvalidException::new);

        participant.startOnboarding();
        UUID submissionId = onboardingService.submitForGuest(participant.getId(), request);
        participant.completeOnboarding(submissionId);

        return onboardingService.getSubmissionResponse(submissionId);
    }

    /** {@link com.yeso.backend.trip.presentation.invite.GuestSessionArgumentResolver}가 재사용한다. */
    @Transactional(readOnly = true)
    public Long resolveGuestParticipantId(String guestSessionToken) {
        tokenGenerator.requireAudience(guestSessionToken, TokenAudience.GUEST_SESSION);
        GuestSession session = guestSessionRepository.findByTokenHash(tokenGenerator.hash(guestSessionToken))
                .orElseThrow(GuestSessionInvalidException::new);
        if (!session.isActive(LocalDateTime.now())) {
            throw new GuestSessionInvalidException();
        }
        return session.getParticipant().getId();
    }

    private TripInvitation requireActiveInvitation(String token) {
        tokenGenerator.requireAudience(token, TokenAudience.INVITE);
        TripInvitation invitation = invitationRepository.findByTokenHash(tokenGenerator.hash(token))
                .orElseThrow(InviteNotFoundException::new);
        if (invitation.isRevoked()) {
            throw new InviteRevokedException();
        }
        if (!invitation.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new InviteExpiredException();
        }
        return invitation;
    }

    private TripInvitation requireInviteOfTrip(Long tripId, Long inviteId) {
        TripInvitation invitation = invitationRepository.findById(inviteId).orElseThrow(InviteNotFoundException::new);
        if (!invitation.getTripPlan().getId().equals(tripId)) {
            throw new InviteNotFoundException();
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

    private static String validateDisplayName(String displayName) {
        if (displayName == null) {
            throw new InvalidDisplayNameException();
        }
        String trimmed = displayName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 30) {
            throw new InvalidDisplayNameException();
        }
        return trimmed;
    }
}
