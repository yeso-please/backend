package com.yeso.backend.trip.presentation.course;

import com.yeso.backend.trip.domain.RestaurantSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 코스(docs/api/trip.md 5장 {@code Course}). 날짜별 순서 목록이며 시각은 없다. 배열 순서가 방문 순서다.
 * 공유 링크 열람자({@code myRole: VIEWER})에게는 참여자 이름이 드러나는 {@code tasteBasis}·{@code updatedBy}와
 * 변경 기록의 사람을 비운다. 초대 링크 둘러보기(4-16)는 같은 VIEWER지만 이름을 보여 준다(2026-10-16 D8).
 */
public record CourseResponse(
        Long tripId, int version, String myRole, String regionSigCd, String regionName,
        LocalDate startDate, LocalDate endDate, String scheduleDensity, String title, String titleSource,
        String intro, String introSource, boolean aiGenerated,
        String recommendationMode, UserRef tasteBasis, List<DayResponse> days, List<WarningResponse> warnings,
        Changes changes, UserRef updatedBy, LocalDateTime updatedAt) {

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
            Integer travelFromPreviousMeters, Travel travelFromPrevious, boolean estimated, String source,
            String reason, String reasonSource, String personalReason, String oneLine, String summaryBasis,
            ItemChange change) implements ItemResponse {
    }

    public record MealItemResponse(
            String itemId, String type, String meal, int durationMinutes, Integer travelFromPreviousMinutes,
            Integer travelFromPreviousMeters, Travel travelFromPrevious, RestaurantSnapshot restaurant)
            implements ItemResponse {
    }

    /**
     * 앞 지점에서 이 지점까지 수단별 이동(D5). 5-7 지도 경로가 기억해 둔 실제 경로가 있으면 그 시간이다: 자동차
     * {@code carSource: ROAD}(카카오모빌리티), 도보·대중교통 {@code walkSource}·{@code transitSource: ROUTE}(카카오맵).
     * 없으면 직선 추정({@code STRAIGHT}). {@code meters}는 직선거리. {@code estimated}는 하나라도 직선 추정이면 true.
     * {@code transitTransfers}·{@code transitFare}는 대중교통 실제 경로가 있을 때만 있다.
     */
    public record Travel(int meters, int walkMinutes, int carMinutes, int transitMinutes, String carSource,
                         String walkSource, String transitSource, Integer transitTransfers, Integer transitFare,
                         boolean estimated) {
    }

    /**
     * 기준점 대비 변경(D11). {@code origin}: BASE(기준 추천) | ADDED(추가) | REPLACED(교체).
     * {@code by}는 마지막으로 추가·교체·되살린 사람(공유 링크 화면이면 null).
     */
    public record ItemChange(String origin, UserRef by, ReplacedFrom replacedFrom, String note, LocalDateTime changedAt) {
    }

    public record ReplacedFrom(Long attractionId, String name) {
    }

    /** 변경 기록 요약. 코스가 없으면 null이다. */
    public record Changes(LocalDateTime baselineAt, List<DayChange> days, List<Removal> removed, LatestChange latest) {
    }

    public record DayChange(int dayIndex, int added, int replaced, int removed) {
    }

    public record Removal(Long removalId, Long attractionId, String name, int dayIndex, String origin, UserRef removedBy,
                          String note, LocalDateTime removedAt) {
    }

    /** 가장 최근 변경 한 건. {@code type}: ADDED | REPLACED | REMOVED | RESTORED. */
    public record LatestChange(String type, int dayIndex, String attractionName, String replacedFromName, UserRef by,
                               String note, LocalDateTime at) {
    }

    /** {@code dayIndex}·{@code itemId}가 null이면 코스 전체에 대한 경고다. */
    public record WarningResponse(String code, Integer dayIndex, String itemId) {
    }
}
