package com.yeso.backend.trip.presentation.context;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.yeso.backend.shared.web.CurrentUserId;
import com.yeso.backend.trip.application.context.TripService;
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

@Tag(name = "3. 여행 context·지역", description = "docs/api/trip.md §3")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @Operation(summary = "3-1 선택 불가 날짜")
    @GetMapping("/unavailable-dates")
    public List<UnavailableDateRangeResponse> unavailableDates(
            @CurrentUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return tripService.getUnavailableDates(userId, from, to);
    }

    @Operation(summary = "3-2 날짜 중복 미리 확인")
    @PostMapping("/context/check")
    public CheckTripContextResponse checkContext(
            @CurrentUserId Long userId, @Valid @RequestBody CheckTripContextRequest request) {
        return tripService.checkContext(userId, request);
    }

    @Operation(summary = "3-3 여행 만들기")
    @PostMapping
    public ResponseEntity<TripContextResponse> create(
            @CurrentUserId Long userId, @Valid @RequestBody CreateTripRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.createTrip(userId, request));
    }

    @Operation(summary = "3-6 내 여행 목록")
    @GetMapping
    public List<MyTripResponse> myTrips(
            @CurrentUserId Long userId, @RequestParam(required = false) TripPeriod period) {
        return tripService.listMyTrips(userId, period);
    }

    @Operation(summary = "3-8 참여자 목록")
    @GetMapping("/{tripId}/participants")
    public List<ParticipantResponse> participants(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return tripService.listParticipants(userId, tripId);
    }

    /** "내 여행 목록에서 삭제". 마지막 참여자가 나가면 여행이 삭제된다. */
    @Operation(summary = "3-9 여행 탈퇴")
    @DeleteMapping("/{tripId}/participants/me")
    public ResponseEntity<Void> leave(@CurrentUserId Long userId, @PathVariable Long tripId) {
        tripService.leave(userId, tripId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "3-4 여행 context 조회")
    @GetMapping("/{tripId}/context")
    public TripContextResponse context(@CurrentUserId Long userId, @PathVariable Long tripId) {
        return tripService.getContext(userId, tripId);
    }

    @Operation(summary = "3-5 이동수단·출발지 수정")
    @PatchMapping("/{tripId}/context")
    public TripContextResponse updateContext(
            @CurrentUserId Long userId, @PathVariable Long tripId, @Valid @RequestBody UpdateTripContextRequest request) {
        return tripService.updateContext(userId, tripId, request);
    }
}
