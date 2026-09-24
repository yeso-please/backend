package com.yeso.backend.trip.presentation;

import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.TripService;
import com.yeso.backend.trip.domain.TripPeriod;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @GetMapping("/unavailable-dates")
    public List<UnavailableDateRangeResponse> unavailableDates(
            @CurrentUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return tripService.getUnavailableDates(userId, from, to);
    }

    @PostMapping("/context/check")
    public TripContextCheckResponse checkContext(
            @CurrentUserId Long userId, @RequestBody TripContextCheckRequest request) {
        return tripService.checkContext(userId, request);
    }

    @PostMapping
    public ResponseEntity<TripContextResponse> create(
            @CurrentUserId Long userId, @RequestBody CreateTripRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.createTrip(userId, request));
    }

    @GetMapping
    public List<MyTripResponse> myTrips(
            @CurrentUserId Long userId, @RequestParam(required = false) TripPeriod period) {
        return tripService.listMyTrips(userId, period);
    }

    @GetMapping("/{id}/participants")
    public List<ParticipantResponse> participants(@CurrentUserId Long userId, @PathVariable Long id) {
        return tripService.listParticipants(userId, id);
    }

    /** "내 여행 목록에서 삭제". 마지막 참여자가 나가면 여행이 삭제된다. */
    @DeleteMapping("/{id}/participants/me")
    public ResponseEntity<Void> leave(@CurrentUserId Long userId, @PathVariable Long id) {
        tripService.leave(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/context")
    public TripContextResponse context(@CurrentUserId Long userId, @PathVariable Long id) {
        return tripService.getContext(userId, id);
    }

    @PatchMapping("/{id}/context")
    public TripContextResponse updateContext(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody UpdateTripContextRequest request) {
        return tripService.updateContext(userId, id, request);
    }
}
