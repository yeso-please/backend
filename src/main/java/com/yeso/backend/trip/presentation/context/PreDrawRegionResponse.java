package com.yeso.backend.trip.presentation.context;

import java.util.List;

/**
 * 7-5 지역 먼저 뽑기 결과(저장하지 않음). {@code maxNights}는 이 지역·밀도로 코스를 만들 수 있는 최대 박수다.
 * 프론트는 날짜 선택에서 이보다 긴 기간을 고르면 "이 기간엔 코스가 부족해요, 다시 뽑을까요?"를 보여 준다.
 */
public record PreDrawRegionResponse(
        String sigCd, String province, String city, Double lat, Double lng, String regionSelection,
        String scheduleDensity, int maxNights, List<String> appliedConditions,
        List<DrawIgnoredConditionResponse> ignoredConditions, long candidateCount, List<String> warnings) {
}
