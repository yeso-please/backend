package com.yeso.backend.trip.application;

import com.yeso.backend.auth.domain.User;
import com.yeso.backend.auth.domain.UserNotFoundException;
import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.trip.domain.InvalidNightsException;
import com.yeso.backend.trip.domain.InvalidOriginException;
import com.yeso.backend.trip.domain.InvalidStartDateException;
import com.yeso.backend.trip.domain.InvalidTransportException;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TripConflict;
import com.yeso.backend.trip.domain.TripContextLockedException;
import com.yeso.backend.trip.domain.TripDateOverlapException;
import com.yeso.backend.trip.domain.TripDayWindowCalculator;
import com.yeso.backend.trip.domain.TripNotFoundException;
import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripPlanStatus;
import com.yeso.backend.trip.domain.TripVersionConflictException;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import com.yeso.backend.trip.presentation.CreateTripRequest;
import com.yeso.backend.trip.presentation.DayWindowResponse;
import com.yeso.backend.trip.presentation.TripContextCheckRequest;
import com.yeso.backend.trip.presentation.TripContextCheckResponse;
import com.yeso.backend.trip.presentation.TripContextResponse;
import com.yeso.backend.trip.presentation.TripConflictResponse;
import com.yeso.backend.trip.presentation.UnavailableDateRangeResponse;
import com.yeso.backend.trip.presentation.UpdateTripContextRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TripService {

    private final TripPlanRepository tripPlanRepository;
    private final TripParticipantRepository tripParticipantRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public TripContextCheckResponse checkContext(Long userId, TripContextCheckRequest request) {
        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        LocalDate endDate = startDate.plusDays(nights);

        List<TripConflict> conflicts = findOverlappingConfirmedTrips(userId, startDate, endDate);
        return new TripContextCheckResponse(
                conflicts.isEmpty(),
                endDate,
                conflicts.stream().map(TripConflictResponse::from).toList(),
                TripDayWindowCalculator.calculate(startDate, nights).stream()
                        .map(DayWindowResponse::from)
                        .toList());
    }

    public TripContextResponse createTrip(Long userId, CreateTripRequest request) {
        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        Transport transport = validateTransport(request.transport());
        validateOrigin(request.originLat(), request.originLng());
        LocalDate endDate = startDate.plusDays(nights);

        List<TripConflict> conflicts = findOverlappingConfirmedTrips(userId, startDate, endDate);
        if (!conflicts.isEmpty()) {
            throw new TripDateOverlapException(conflicts);
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        TripPlan tripPlan = new TripPlan(user, startDate, nights, transport, request.originLat(), request.originLng());
        tripPlanRepository.save(tripPlan);
        tripParticipantRepository.save(TripParticipant.owner(tripPlan, user));

        return TripContextResponse.of(tripPlan, false);
    }

    @Transactional(readOnly = true)
    public TripContextResponse getContext(Long userId, Long tripId) {
        TripPlan tripPlan = findOwnedTrip(userId, tripId);
        return TripContextResponse.of(tripPlan, false);
    }

    public TripContextResponse updateContext(
            Long userId, Long tripId, UpdateTripContextRequest request) {
        TripPlan tripPlan = findOwnedTrip(userId, tripId);
        if (!tripPlan.isMutable()) {
            throw new TripContextLockedException();
        }
        if (!request.version().equals(tripPlan.getVersion())) {
            throw new TripVersionConflictException();
        }

        LocalDate startDate = validateStartDate(request.startDate());
        int nights = validateNights(request.nights());
        Transport transport = validateTransport(request.transport());
        validateOrigin(request.originLat(), request.originLng());
        LocalDate endDate = startDate.plusDays(nights);

        List<TripConflict> conflicts = findOverlappingConfirmedTrips(userId, startDate, endDate);
        if (!conflicts.isEmpty()) {
            throw new TripDateOverlapException(conflicts);
        }

        tripPlan.updateContext(startDate, nights, transport, request.originLat(), request.originLng());
        try {
            tripPlanRepository.saveAndFlush(tripPlan);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new TripVersionConflictException();
        }

        return TripContextResponse.of(tripPlan, true);
    }

    @Transactional(readOnly = true)
    public List<UnavailableDateRangeResponse> getUnavailableDates(Long userId, LocalDate from, LocalDate to) {
        return findOverlappingConfirmedTrips(userId, from, to).stream()
                .map(conflict -> new UnavailableDateRangeResponse(
                        conflict.tripId(), conflict.startDate(), conflict.endDate()))
                .toList();
    }

    private TripPlan findOwnedTrip(Long userId, Long tripId) {
        TripPlan tripPlan = tripPlanRepository.findById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        if (!tripPlan.isOwnedBy(userId)) {
            throw new TripNotFoundException(tripId);
        }
        return tripPlan;
    }

    private List<TripConflict> findOverlappingConfirmedTrips(Long ownerId, LocalDate startDate, LocalDate endDate) {
        return tripPlanRepository
                .findByOwnerUserIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        ownerId, TripPlanStatus.CONFIRMED, endDate, startDate)
                .stream()
                .map(TripConflict::from)
                .toList();
    }

    private static LocalDate validateStartDate(LocalDate startDate) {
        if (startDate == null || !startDate.isAfter(LocalDate.now())) {
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
            throw new InvalidOriginException();
        }
        if (lat != null && (lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
            throw new InvalidOriginException();
        }
    }
}
