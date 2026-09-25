package com.yeso.backend.trip.presentation.context;

import com.yeso.backend.trip.domain.TripPlan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 여행 목록의 원소. {@code myDiaryId}는 여행기 기능이 생기기 전까지 항상 null이다.
 */
public record MyTripResponse(
        Long tripId,
        String title,
        String regionSigCd,
        String regionName,
        LocalDate startDate,
        LocalDate endDate,
        int nights,
        List<ParticipantSummaryResponse> participants,
        boolean hasCourse,
        Long myDiaryId,
        LocalDateTime updatedAt
) {
    public static MyTripResponse of(TripPlan tripPlan, List<ParticipantSummaryResponse> participants, boolean hasCourse) {
        var region = tripPlan.getRegion();
        return new MyTripResponse(
                tripPlan.getId(),
                tripPlan.getTitle(),
                region == null ? null : region.getSigCd(),
                region == null ? null : region.getProvince() + " " + region.getCity(),
                tripPlan.getStartDate(),
                tripPlan.getEndDate(),
                tripPlan.getNights(),
                participants,
                hasCourse,
                null,
                tripPlan.getUpdatedAt());
    }
}
