package com.yeso.backend.trip.presentation.invite;

import java.time.LocalDate;
import java.util.List;

/**
 * 공유 링크로 보는 코스. 코스 저장(docs/api/trip.md 5장) 구현 전이라 {@code days}는 항상 빈 배열이며,
 * 이후 docs/api/trip.md의 {@code Course} 전체로 확장한다.
 */
public record SharedCourseViewResponse(
        Long tripId, String myRole, LocalDate startDate, LocalDate endDate, List<Object> days
) {
    public static final String VIEWER = "VIEWER";
}
