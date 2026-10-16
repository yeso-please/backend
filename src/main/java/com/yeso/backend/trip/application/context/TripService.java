package com.yeso.backend.trip.application.context;

import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.trip.domain.InvalidNightsException;
import com.yeso.backend.trip.domain.InvalidOriginException;
import com.yeso.backend.trip.domain.InvalidStartDateException;
import com.yeso.backend.trip.domain.InvalidTransportException;
import com.yeso.backend.trip.domain.OnboardingRequiredException;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TripConflict;
import com.yeso.backend.trip.application.course.CourseStorage;
import com.yeso.backend.trip.domain.TripDateOverlapException;
import com.yeso.backend.trip.domain.TripDatesImmutableException;
import com.yeso.backend.trip.domain.TripEndedException;
import com.yeso.backend.trip.domain.TripFullException;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.domain.RegionNotFoundException;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.trip.domain.DrawInvalidModeException;
import com.yeso.backend.trip.domain.DrawRegionNotEligibleException;
import com.yeso.backend.trip.domain.InvalidKickTargetException;
import com.yeso.backend.trip.domain.InvalidScheduleDensityException;
import com.yeso.backend.trip.domain.RegionSelectionMode;
import com.yeso.backend.trip.domain.TripManagerOnlyException;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripParticipantNotFoundException;
import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripPeriod;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.TravelDiaryRepository;
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

    /** 이동수단을 생략하고 만든 여행의 기본값(Q3). */
    static final Transport DEFAULT_TRANSPORT = Transport.CAR;

    private final TripPlanRepository tripPlanRepository;
    private final TripParticipantRepository tripParticipantRepository;
    private final CourseItemRepository courseItemRepository;
    private final TravelDiaryRepository travelDiaryRepository;
    private final CourseStorage courseStorage;
    private final RegionEligibilityService regionEligibilityService;
    private final RegionRepository regionRepository;
    private final OnboardingQueryService onboardingQueryService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CheckTripContextResponse checkContext(Long userId, CheckTripContextRequest request) {
        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        LocalDate endDate = startDate.plusDays(nights);

        List<TripConflict> conflicts = findConflicts(userId, startDate, endDate);
        long eligibleRegionCount = regionEligibilityService.countEligible(nights + 1, "RELAXED");
        // 지역을 먼저 골랐으면(D1) 그 지역이 이 일수·밀도로 코스를 만들 수 있는지도 본다. 밀도는 요청 → 설문 → RELAXED.
        Boolean regionEligible = null;
        if (request.sigCd() != null) {
            String density = resolveDensity(request.scheduleDensity(), userId);
            requireRegion(request.sigCd());
            regionEligible = regionEligibilityService.isDrawEligible(request.sigCd(), nights + 1, density);
        }
        return new CheckTripContextResponse(
                conflicts.isEmpty(),
                endDate,
                conflicts.stream().map(TripConflictResponse::from).toList(),
                eligibleRegionCount,
                regionEligible);
    }

    public TripContextResponse createTrip(Long userId, CreateTripRequest request) {
        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        // 이동수단은 선택값이다. 생략하면 자동차(D2·Q3). 3-5로 나중에 바꾼다.
        Transport transport = request.transport() == null ? DEFAULT_TRANSPORT : validateTransport(request.transport());
        validateOrigin(request.originLat(), request.originLng());
        RegionSelectionMode selection = request.regionSigCd() == null ? null : parseSelection(request.regionSelection());

        User user = lockOnboardedUser(userId);
        requireNoConflict(userId, startDate, startDate.plusDays(nights));

        TripPlan tripPlan = new TripPlan(user, startDate, nights, transport, request.originLat(), request.originLng());
        tripPlan.markRetroactiveIfEnded(LocalDate.now(clock));
        if (request.regionSigCd() != null) {
            // 지역을 먼저 고른 흐름(D1): 지역까지 한 번에 저장한다. 이 일수·밀도로 코스를 못 만들면 422.
            Region region = requireRegion(request.regionSigCd());
            String density = resolveDensity(request.scheduleDensity(), userId);
            if (!regionEligibilityService.isDrawEligible(region.getSigCd(), nights + 1, density)) {
                throw new DrawRegionNotEligibleException(region.getSigCd());
            }
            tripPlan.setRegion(region);
            tripPlan.setRegionSelection(selection.name());
            tripPlan.setScheduleDensity(density);
        } else if (request.scheduleDensity() != null) {
            tripPlan.setScheduleDensity(validateDensity(request.scheduleDensity()));
        }
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
        Transport transport = request.transport() == null ? null : validateTransport(request.transport());
        validateOrigin(request.originLat(), request.originLng());
        if (!tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, userId)) {
            throw new TripNotFoundException(tripId);
        }
        TripPlan tripPlan = tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        requireNotEnded(tripPlan);
        if (!request.version().equals(tripPlan.getVersion())) {
            throw new TripVersionConflictException();
        }
        boolean hasCourse = hasCourse(tripId);
        boolean transportChanged = transport != null && transport != tripPlan.getTransport();
        tripPlan.updateTransportAndOrigin(transport, request.originLat(), request.originLng());
        if (hasCourse && transportChanged) {
            courseStorage.recalculateTravel(tripPlan);
        }
        try {
            tripPlanRepository.saveAndFlush(tripPlan);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new TripVersionConflictException();
        }
        return TripContextResponse.of(tripPlan, hasCourse);
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
        TripPlan tripPlan = requireParticipantTrip(userId, tripId);
        List<TripParticipant> participants = tripParticipantRepository.findByTripPlanIdOrderByCreatedAtAsc(tripId);
        Long managerId = managerOf(tripPlan, participants);
        return participants.stream()
                .map(participant -> ParticipantResponse.from(participant, participant.getUser().getId().equals(managerId)))
                .toList();
    }

    /**
     * 여행 관리자(참여자 내보내기 권한, D7). 생성자가 참여 중이면 생성자, 생성자가 나갔으면 가장 먼저 들어온 참여자다(Q7).
     *
     * @param participants 참여 순서대로 정렬된 참여자
     */
    static Long managerOf(TripPlan tripPlan, List<TripParticipant> participants) {
        Long creatorId = tripPlan.getOwnerUser().getId();
        if (participants.stream().anyMatch(participant -> participant.getUser().getId().equals(creatorId))) {
            return creatorId;
        }
        return participants.isEmpty() ? null : participants.get(0).getUser().getId();
    }

    /**
     * 3-10 참여자 내보내기. 관리자만, 자기 자신은 안 된다(3-9 탈퇴를 쓴다). 내보낸 사람의 캘린더에서 여행이 사라지고
     * 그 기간을 다시 쓸 수 있다. 변경 기록에 남은 그 사람의 닉네임은 그대로 둔다. 다시 초대하면 다시 참여할 수 있다.
     */
    public void kick(Long userId, Long tripId, Long targetUserId) {
        TripPlan tripPlan = tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        if (!tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, userId)) {
            throw new TripNotFoundException(tripId);
        }
        if (userId.equals(targetUserId)) {
            throw new InvalidKickTargetException();
        }
        List<TripParticipant> participants = tripParticipantRepository.findByTripPlanIdOrderByCreatedAtAsc(tripId);
        if (!userId.equals(managerOf(tripPlan, participants))) {
            throw new TripManagerOnlyException(tripId);
        }
        requireNotEnded(tripPlan);
        TripParticipant target = participants.stream()
                .filter(participant -> participant.getUser().getId().equals(targetUserId))
                .findFirst()
                .orElseThrow(() -> new TripParticipantNotFoundException(targetUserId));
        tripParticipantRepository.delete(target);
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
        requireInvitable(tripPlan);
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

    /**
     * 여행 행을 잠그고 참여자 여행을 돌려준다. 여행기가 만들어지는 순간 코스·지역 편집과 겹치지 않게 한다
     * (여행기는 만들 때의 지역·제목을 복사한다).
     */
    public TripPlan requireParticipantTripForUpdate(Long userId, Long tripId) {
        requireParticipantTrip(userId, tripId);
        return tripPlanRepository.lockById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
    }

    /**
     * 종료일이 지난 여행은 읽기 전용이다. 여행을 바꾸는 유스케이스가 먼저 호출한다. 예외로 사후 기록 여행은
     * 여행기(어느 참여자의 것이든)가 하나도 없는 동안 고칠 수 있다.
     */
    public void requireNotEnded(TripPlan tripPlan) {
        if (!tripPlan.isEnded(LocalDate.now(clock))) {
            return;
        }
        if (tripPlan.isRetroactive() && !travelDiaryRepository.existsByTripId(tripPlan.getId())) {
            return;
        }
        throw new TripEndedException(tripPlan.getId());
    }

    /** 초대 발급·수락 검사. 사후 기록 여행은 혼자 쓰는 기록이라 종료일이 지나면 항상 막는다. */
    public void requireInvitable(TripPlan tripPlan) {
        if (tripPlan.isEnded(LocalDate.now(clock))) {
            throw new TripEndedException(tripPlan.getId());
        }
    }

    /** 받은 친구 초대 목록(4-7)의 {@code dateConflict}. 이 기간이 회원의 참여 중인 여행과 겹치면 true. */
    @Transactional(readOnly = true)
    public boolean hasDateConflict(Long userId, LocalDate startDate, LocalDate endDate) {
        return !findConflicts(userId, startDate, endDate).isEmpty();
    }

    @Transactional(readOnly = true)
    public boolean isParticipant(Long userId, Long tripId) {
        return tripParticipantRepository.existsByTripPlanIdAndUserId(tripId, userId);
    }

    /** 참여 순서대로 닉네임(초대 미리보기 4-4·받은 초대 4-7, D8). */
    @Transactional(readOnly = true)
    public List<String> participantNicknames(Long tripId) {
        return tripParticipantRepository.findByTripPlanIdOrderByCreatedAtAsc(tripId).stream()
                .map(participant -> participant.getUser().getNickname())
                .toList();
    }

    @Transactional(readOnly = true)
    public long countParticipants(Long tripId) {
        return tripParticipantRepository.countByTripPlanId(tripId);
    }

    private Region requireRegion(String sigCd) {
        return regionRepository.findById(sigCd).orElseThrow(() -> new RegionNotFoundException(sigCd));
    }

    /** 요청 → 요청자의 최신 설문 밀도 → RELAXED. */
    private String resolveDensity(String requested, Long userId) {
        if (requested != null) {
            return validateDensity(requested);
        }
        return onboardingQueryService.findLatestScheduleDensity(userId).orElse("RELAXED");
    }

    private static String validateDensity(String density) {
        if (!density.equals("RELAXED") && !density.equals("PACKED")) {
            throw new InvalidScheduleDensityException();
        }
        return density;
    }

    /** 지역을 먼저 고른 방식. 생략하면 지도에서 직접 고른 것(MANUAL)으로 본다. */
    private static RegionSelectionMode parseSelection(String selection) {
        if (selection == null) {
            return RegionSelectionMode.MANUAL;
        }
        try {
            return RegionSelectionMode.valueOf(selection);
        } catch (IllegalArgumentException e) {
            throw new DrawInvalidModeException();
        }
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
        if (startDate == null) {
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
