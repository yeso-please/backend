package com.yeso.backend.trip.presentation.course;

import java.util.List;

/**
 * 5-3. 각 operation의 필드 조합은 서비스에서 COURSE_INVALID_OPERATION으로 검증한다.
 * {@code note}는 REMOVE·REPLACE의 선택 사유(100자), {@code removalId}는 RESTORE 대상 제외 기록,
 * {@code meal}은 ADD_MEAL로 다시 넣을 끼니({@code LUNCH}·{@code DINNER})다.
 */
public record EditCourseRequest(Integer version, List<Operation> operations) {
    public record Operation(
            String op, String itemId, Integer dayIndex, Integer position, Long attractionId,
            String selectionToken, Long removalId, String note, String meal) {
    }
}
