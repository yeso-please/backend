package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 관광지를 하루 최소 1곳도 배치하지 못하는 날이 있다(docs/api/trip.md 5-1).
 * {@code details.days}는 모자란 날마다 {@code {dayIndex, required, available}}이다.
 */
public class CourseInsufficientCandidatesException extends TripException {

    public record DayShortage(int dayIndex, int required, int available) {
    }

    public CourseInsufficientCandidatesException(List<DayShortage> days) {
        super(ErrorCode.COURSE_INSUFFICIENT_CANDIDATES, "코스를 채울 관광지가 부족합니다.", Map.of("days", days));
    }
}
