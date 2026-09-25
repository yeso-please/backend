package com.yeso.backend.trip.application.context;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.trip.domain.InvalidNightsException;
import com.yeso.backend.trip.domain.InvalidOriginException;
import com.yeso.backend.trip.domain.InvalidStartDateException;
import com.yeso.backend.trip.domain.InvalidTransportException;
import com.yeso.backend.trip.domain.OnboardingRequiredException;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TripConflict;
import com.yeso.backend.trip.domain.TripContextLockedException;
import com.yeso.backend.trip.domain.TripDateOverlapException;
import com.yeso.backend.trip.domain.TripDatesImmutableException;
import com.yeso.backend.trip.domain.TripEndedException;
import com.yeso.backend.trip.domain.TripFullException;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripPeriod;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.presentation.context.CreateTripRequest;
import com.yeso.backend.trip.presentation.context.MyTripResponse;
import com.yeso.backend.trip.presentation.context.ParticipantResponse;
import com.yeso.backend.trip.presentation.context.TripConflictResponse;
import com.yeso.backend.trip.presentation.context.CheckTripContextRequest;
import com.yeso.backend.trip.presentation.context.CheckTripContextResponse;
import com.yeso.backend.trip.presentation.context.TripContextResponse;
import com.yeso.backend.trip.presentation.context.UnavailableDateRangeResponse;
import com.yeso.backend.trip.presentation.context.UpdateTripContextRequest;
import com.yeso.backend.trip.presentation.context.ParticipantSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 여행 context·날짜 중복 차단·참여·탈퇴(2026-09-24 정책). 여행은 만드는 순간 기간을 차지하고,
 * 내가 만들었거나 참여 중인 여행과 겹치는 날짜는 쓸 수 없다. 참여자는 모두 동등하며 삭제 대신
 * 개인 탈퇴만 있다 — 마지막 참여자가 나가면 여행을 지운다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TripService {

    private final TripPlanRepository tripPlanRepository;
    private final TripParticipantRepository tripParticipantRepository;
    private final CourseItemRepository courseItemRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CheckTripContextResponse checkContext(Long userId, CheckTripContextRequest request) {
        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        LocalDate endDate = startDate.plusDays(nights);

        List<TripConflict> conflicts = findConflicts(userId, startDate, endDate);
        return new CheckTripContextResponse(
                conflicts.isEmpty(),
                endDate,
                conflicts.stream().map(TripConflictResponse::from).toList());
    }

    public TripContextResponse createTrip(Long userId, CreateTripRequest request) {
        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        Transport transport = validateTransport(request.transport());
        validateOrigin(request.originLat(), request.originLng());

        User user = lockOnboardedUser(userId);
        requireNoConflict(userId, startDate, startDate.plusDays(nights));

        TripPlan tripPlan = new TripPlan(user, startDate, nights, transport, request.originLat(), request.originLng());
        tripPlanRepository.save(tripPlan);
        tripParticipantRepository.save(TripParticipant.creator(tripPlan, user));

        return TripContextResponse.of(tripPlan, false);
    }

    @Transactional(readOnly = true)
    public TripContextResponse getContext(Long userId, Long tripId) {
        TripPlan tripPlan = requireParticipantTrip(userId, tripId);
        return TripContextResponse.of(tripPlan, hasCourse(tripId));
    }

    public TripContextResponse updateContext(Long userId, Long tripId, UpdateTripContextRequest request) {
        if (request.startDate() != null || request.nights() != null) {
            throw new TripDatesImmutableException();
        }
        TripPlan tripPlan = requireParticipantTrip(userId, tripId);
        requireNotEnded(tripPlan);
        if (!request.version().equals(tripPlan.getVersion())) {
            throw new TripVersionConflictException();
        }
        if (hasCourse(tripId)) {
            throw new TripContextLockedException();
        }
        Transport transport = request.transport() == null ? null : validateTransport(request.transport());
        validateOrigin(request.originLat(), request.originLng());

        tripPlan.updateTransportAndOrigin(transport, request.originLat(), request.originLng());
        try {
            tripPlanRepository.saveAndFlush(tripPlan);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new TripVersionConflictException();
        }
        return TripContextResponse.of(tripPlan, false);
    }

    @Transactional(readOnly = true)
    public List<UnavailableDateRangeResponse> getUnavailableDates(Long userId, LocalDate from, LocalDate to) {
        return tripParticipantRepository.findOverlappingTrips(userId, from, to).stream()
                .map(trip -> new UnavailableDateRangeResponse(trip.getId(), trip.getStartDate(), trip.getEndDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyTripResponse> listMyTrips(Long userId, TripPeriod period) {
        LocalDate today = LocalDate.now(clock);
        List<TripPlan> trips = tripParticipantRepository.findTripsOf(userId).stream()
                .filter(trip -> period == null || (period == TripPeriod.PAST) == trip.isEnded(today))
                .toList();
        if (trips.isEmpty()) {
            return List.of();
        }
        List<Long> tripIds = trips.stream().map(TripPlan::getId).toList();
        Map<Long, List<ParticipantSummaryResponse>> participantsByTrip = tripParticipantRepository
                .findWithUserByTripPlanIdIn(tripIds).stream()
                .collect(Collectors.groupingBy(
                        participant -> participant.getTripPlan().getId(),
                        Collectors.mapping(participant -> ParticipantSummaryResponse.from(participant.getUser()), Collectors.toList())));
        Set<Long> tripsWithCourse = courseItemRepository.findTripPlanIdsWithItems(tripIds);
        return trips.stream()
                .map(trip -> MyTripResponse.of(
                        trip, participantsByTrip.getOrDefault(trip.getId(), List.of()), tripsWithCourse.contains(trip.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ParticipantResponse> listParticipants(Long userId, Long tripId) {
        requireParticipantTrip(userId, tripId);
        return tripParticipantRepository.findByTripPlanIdOrderByCreatedAtAsc(tripId).stream()
                .map(ParticipantResponse::from)
                .toList();
    }

    public void leave(Long userId, Long tripId) {
        TripPlan tripPlan = tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        TripParticipant participant = tripParticipantRepository.findByTripPlanIdAndUserId(tripId, userId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        tripParticipantRepository.delete(participant);
        tripParticipantRepository.flush();
        if (tripParticipantRepository.countByTripPlanId(tripId) == 0) {
            // 초대·공유 링크·세션·코스는 DB FK cascade로 함께 지워진다.
            tripPlanRepository.delete(tripPlan);
        }
    }

    /**
     * 초대 수락이 재사용한다. 설문을 마친 회원만, 정원(8명) 미만이고 자기 여행과 날짜가 겹치지 않을 때 참여한다.
     *
     * @return 새로 참여했으면 true, 이미 참여 중이면 false(멱등)
     */
    public boolean joinAsMember(Long userId, Long tripId, Long invitationId) {
        TripPlan tripPlan = tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        requireNotEnded(tripPlan);
        User user = lockOnboardedUser(userId);
        if (tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, userId)) {
            return false;
        }
        // 여행 행 잠금(lockById) 안에서 세므로 동시 수락으로 정원을 넘지 않는다.
        if (tripParticipantRepository.countByTripPlanId(tripId) >= TripPlan.MAX_PARTICIPANTS) {
            throw new TripFullException(tripId);
        }
        requireNoConflict(userId, tripPlan.getStartDate(), tripPlan.getEndDate());
        tripParticipantRepository.save(TripParticipant.member(tripPlan, user, invitationId));
        return true;
    }

    @Transactional(readOnly = true)
    public TripContextResponse contextOf(TripPlan tripPlan) {
        return TripContextResponse.of(tripPlan, hasCourse(tripPlan.getId()));
    }

    /** invite/share 유스케이스가 권한 확인에 재사용한다. 참여자가 아니면 존재를 숨기고 404다. */
    @Transactional(readOnly = true)
    public TripPlan requireParticipantTrip(Long userId, Long tripId) {
        TripPlan tripPlan = tripPlanRepository.findById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        if (!tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, userId)) {
            throw new TripNotFoundException(tripId);
        }
        return tripPlan;
    }

    /** 종료일이 지난 여행은 읽기 전용이다. 여행을 바꾸는 유스케이스가 먼저 호출한다. */
    public void requireNotEnded(TripPlan tripPlan) {
        if (tripPlan.isEnded(LocalDate.now(clock))) {
            throw new TripEndedException(tripPlan.getId());
        }
    }

    @Transactional(readOnly = true)
    public long countParticipants(Long tripId) {
        return tripParticipantRepository.countByTripPlanId(tripId);
    }

    private User lockOnboardedUser(Long userId) {
        User user = tripPlanRepository.lockUser(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (!user.isOnboardingCompleted()) {
            throw new OnboardingRequiredException();
        }
        return user;
    }

    private void requireNoConflict(Long userId, LocalDate startDate, LocalDate endDate) {
        List<TripConflict> conflicts = findConflicts(userId, startDate, endDate);
        if (!conflicts.isEmpty()) {
            throw new TripDateOverlapException(conflicts);
        }
    }

    private List<TripConflict> findConflicts(Long userId, LocalDate startDate, LocalDate endDate) {
        return tripParticipantRepository.findOverlappingTrips(userId, startDate, endDate).stream()
                .map(TripConflict::from)
                .toList();
    }

    private boolean hasCourse(Long tripId) {
        return courseItemRepository.existsByTripPlanId(tripId);
    }

    private LocalDate validateStartDate(LocalDate startDate) {
        if (startDate == null || !startDate.isAfter(LocalDate.now(clock))) {
            throw new InvalidStartDateException();
        }
        return startDate;
    }

    private static int validateNights(Integer nights) {
        if (nights == null || nights < 0 || nights > 6) {
            throw new InvalidNightsException();
        }
        return nights;
    }

    private static Transport validateTransport(String transport) {
        if (transport == null) {
            throw new InvalidTransportException();
        }
        try {
            return Transport.valueOf(transport);
        } catch (IllegalArgumentException e) {
            throw new InvalidTransportException();
        }
    }

    private static void validateOrigin(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw InvalidOriginException.missingPair();
        }
        if (lat != null && (lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
            throw InvalidOriginException.outOfRange();
        }
    }
}
