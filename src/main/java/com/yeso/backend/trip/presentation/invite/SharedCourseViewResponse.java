package com.yeso.backend.trip.presentation.invite;

import java.time.LocalDate;
import java.util.List;

/**
 * 공유된 확정 일정 조회 응답. 실제 관광지/식당 stop은 코스 생성·편집·확정(WORK-06/07/08)이
 * 아직 없어 항상 빈 배열이다 — trip context만 먼저 노출한다(WORK-04 범위 명시).
 */
public record SharedCourseViewResponse(
        Long tripId, String permission, String status, LocalDate startDate, LocalDate endDate, List<Object> stops
) {
}
