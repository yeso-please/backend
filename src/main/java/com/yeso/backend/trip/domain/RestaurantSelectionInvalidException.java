package com.yeso.backend.trip.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/**
 * 식당 {@code selectionToken}이 위조·만료됐거나 다른 여행·슬롯의 것일 때(docs/api/trip.md 5-3).
 * 어느 검사에서 실패했는지는 응답에 드러내지 않는다.
 */
public class RestaurantSelectionInvalidException extends TripException {
    public RestaurantSelectionInvalidException() {
        super(ErrorCode.COURSE_RESTAURANT_SELECTION_INVALID, "식당 선택 정보가 올바르지 않거나 만료됐습니다. 다시 검색해 주세요.");
    }
}
