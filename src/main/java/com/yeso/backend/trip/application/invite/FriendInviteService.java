package com.yeso.backend.trip.application.invite;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.application.friend.FriendQueryService;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.FriendInvitationNotFoundException;
import com.yeso.backend.trip.domain.FriendInvitationStatus;
import com.yeso.backend.trip.domain.InviteAlreadyHandledException;
import com.yeso.backend.trip.domain.NotFriendException;
import com.yeso.backend.trip.domain.TripFriendInvitation;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.TripFriendInvitationRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.context.TripContextResponse;
import com.yeso.backend.trip.presentation.invite.FriendInviteResponse;
import com.yeso.backend.trip.presentation.invite.ReceivedTripInviteResponse;
import com.yeso.backend.trip.presentation.invite.SentFriendInviteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 친구 목록에서 바로 보내는 여행 초대(docs/api/trip.md 4-6~4-8, 4-14, 4-15). 참여자 누구나 보내고 취소하며,
 * 받은 사람은 받은 초대함에서 수락·거절한다. 수락은 초대 링크 수락과 같은 참여 경로
 * ({@link TripService#joinAsMember})를 써서 정원·날짜 겹침·설문 조건을 한 곳에서 지킨다.
 *
 * <p>잠금 순서는 여행 → 초대다. 초대 링크 수락(4-5)도 여행을 먼저 잠그고 대기 중인 친구 초대를 바꾸므로
 * 두 경로가 서로를 기다리지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FriendInviteService {

    private final TripFriendInvitationRepository invitationRepository;
    private final TripPlanRepository tripPlanRepository;
    private final UserRepository userRepository;
    private final TripService tripService;
    private final FriendQueryService friendQueryService;
    private final Clock clock;

    /**
     * 대기 중인 초대가 이미 있으면 누가 보냈든 새로 만들지 않고 그 초대를 {@code created=false}로 돌려준다(멱등).
     * 친구 관계는 보낼 때만 확인한다.
     */
    public InviteResult invite(Long userId, Long tripId, Long friendUserId) {
        TripPlan tripPlan = tripService.requireParticipantTrip(userId, tripId);
        if (!friendQueryService.areFriends(userId, friendUserId)) {
            throw new NotFriendException(friendUserId);
        }
        tripService.requireNotEnded(tripPlan);
        // 같은 여행의 초대 보내기를 직렬화해 대기 중인 초대가 둘 생기지 않게 한다.
        tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        if (tripService.isParticipant(friendUserId, tripId)) {
            throw InviteAlreadyHandledException.alreadyParticipant();
        }

        return invitationRepository
                .findByTripPlanIdAndInviteeIdAndStatus(tripId, friendUserId, FriendInvitationStatus.PENDING)
                .map(pending -> new InviteResult(false, FriendInviteResponse.from(pending)))
                .orElseGet(() -> {
                    TripFriendInvitation invitation = invitationRepository.save(
                            new TripFriendInvitation(tripPlan, requireUser(userId), requireUser(friendUserId)));
                    return new InviteResult(true, FriendInviteResponse.from(invitation));
                });
    }

    public record InviteResult(boolean created, FriendInviteResponse invite) {
    }

    @Transactional(readOnly = true)
    public List<ReceivedTripInviteResponse> listReceived(Long userId) {
        return invitationRepository.findReceived(userId, FriendInvitationStatus.PENDING).stream()
                .map(invitation -> {
                    TripPlan tripPlan = invitation.getTripPlan();
                    boolean dateConflict = tripService.hasDateConflict(
                            userId, tripPlan.getStartDate(), tripPlan.getEndDate());
                    return ReceivedTripInviteResponse.of(invitation, dateConflict);
                })
                .toList();
    }

    /** 초대 링크 수락과 같은 참여 경로를 쓴다. 종료·설문·정원·날짜 겹침은 {@link TripService#joinAsMember}가 검사한다. */
    public TripContextResponse accept(Long userId, Long invitationId) {
        TripFriendInvitation found = requireReceived(userId, invitationId);
        Long tripId = found.getTripPlan().getId();
        TripPlan tripPlan = tripPlanRepository.lockById(tripId).orElseThrow(FriendInvitationNotFoundException::new);
        TripFriendInvitation invitation = lockReceived(userId, invitationId);

        tripService.requireNotEnded(tripPlan);
        if (!invitation.isPending()) {
            throw new InviteAlreadyHandledException();
        }
        tripService.joinAsMember(userId, tripId, null);
        invitation.accept(LocalDateTime.now(clock));
        return tripService.contextOf(tripPlan);
    }

    /** 종료된 여행의 초대도 거절할 수 있다. */
    public void decline(Long userId, Long invitationId) {
        lockReceived(userId, invitationId).decline(LocalDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public List<SentFriendInviteResponse> listSent(Long userId, Long tripId) {
        tripService.requireParticipantTrip(userId, tripId);
        return invitationRepository.findSent(tripId).stream()
                .map(SentFriendInviteResponse::from)
                .toList();
    }

    /** 보낸 사람이 아니어도 참여자 누구나 대기 중인 초대를 취소한다. */
    public void cancel(Long userId, Long tripId, Long invitationId) {
        tripService.requireParticipantTrip(userId, tripId);
        TripFriendInvitation invitation = invitationRepository.lockById(invitationId)
                .filter(found -> found.belongsTo(tripId))
                .orElseThrow(FriendInvitationNotFoundException::new);
        invitation.cancel(LocalDateTime.now(clock));
    }

    private TripFriendInvitation requireReceived(Long userId, Long invitationId) {
        return invitationRepository.findById(invitationId)
                .filter(invitation -> invitation.isFor(userId))
                .orElseThrow(FriendInvitationNotFoundException::new);
    }

    private TripFriendInvitation lockReceived(Long userId, Long invitationId) {
        return invitationRepository.lockById(invitationId)
                .filter(invitation -> invitation.isFor(userId))
                .orElseThrow(FriendInvitationNotFoundException::new);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }
}
