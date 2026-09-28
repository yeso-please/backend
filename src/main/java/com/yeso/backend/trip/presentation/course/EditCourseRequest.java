package com.yeso.backend.trip.presentation.course;

import java.util.List;

/** 5-3. 각 operation의 필드 조합은 서비스에서 COURSE_INVALID_OPERATION으로 검증한다. */
public record EditCourseRequest(Integer version, List<Operation> operations) {
    public record Operation(
            String op, String itemId, Integer dayIndex, Integer position, Long attractionId,
            String selectionToken) {
    }
}
