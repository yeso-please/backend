package com.yeso.backend.trip.presentation.course;

import com.yeso.backend.trip.domain.RestaurantSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 코스(docs/api/trip.md 5장 {@code Course}). 날짜별 순서 목록이며 시각은 없다. 배열 순서가 방문 순서다.
 * 공유 링크 열람자({@code myRole: VIEWER})에게는 참여자 이름이 드러나는 {@code tasteBasis}·{@code updatedBy}를 비운다.
 */
public record CourseResponse(
        Long tripId, int version, String myRole, String regionSigCd, String regionName,
        LocalDate startDate, LocalDate endDate, String scheduleDensity, String title, String titleSource,
        String recommendationMode, UserRef tasteBasis, List<DayResponse> days, List<WarningResponse> warnings,
        UserRef updatedBy, LocalDateTime updatedAt) {

    public static final String PARTICIPANT = "PARTICIPANT";
    public static final String VIEWER = "VIEWER";

    public record UserRef(Long userId, String nickname) {
    }

    public record DayResponse(int dayIndex, LocalDate date, List<ItemResponse> items) {
    }

    /** 항목 타입별로 필드가 다르다. 표에 없는 필드는 그 타입에 없다. */
    public sealed interface ItemResponse permits AttractionItemResponse, MealItemResponse {
    }

    public record AttractionItemResponse(
            String itemId, String type, Long attractionId, String name, String category, String thumbnailUrl,
            String address, Double lat, Double lng, int durationMinutes, Integer travelFromPreviousMinutes,
            boolean estimated, String source, String reason) implements ItemResponse {
    }

    public record MealItemResponse(
            String itemId, String type, String meal, int durationMinutes, RestaurantSnapshot restaurant)
            implements ItemResponse {
    }

    /** {@code dayIndex}·{@code itemId}가 null이면 코스 전체에 대한 경고다. */
    public record WarningResponse(String code, Integer dayIndex, String itemId) {
    }
}
