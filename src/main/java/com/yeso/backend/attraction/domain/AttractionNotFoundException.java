package com.yeso.backend.attraction.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 없는 관광지. 쇼핑·숙박·음식점처럼 코스 후보가 아닌 장소도 여기에 해당한다. */
public class AttractionNotFoundException extends AttractionException {
    public AttractionNotFoundException(Long attractionId) {
        super(ErrorCode.ATTRACTION_NOT_FOUND, "관광지를 찾을 수 없습니다: " + attractionId);
    }
}
