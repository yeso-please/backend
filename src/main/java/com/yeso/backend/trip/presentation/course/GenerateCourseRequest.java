package com.yeso.backend.trip.presentation.course;

import jakarta.validation.constraints.NotNull;

/**
 * @param scheduleDensity 생략하면 여행 밀도(없으면 요청자 온보딩 밀도). 보내면 여행 밀도도 이 값으로 바뀐다
 * @param tasteMode       {@code TASTE}(취향 반영 랜덤) | {@code RANDOM}(완전 랜덤). 생략하면 요청자의 프로필 설정(2-11)
 * @param version         직전에 받은 여행 버전
 */
public record GenerateCourseRequest(String scheduleDensity, String tasteMode, @NotNull Integer version) {
}
