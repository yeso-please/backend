package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.trip.domain.MealType;
import com.yeso.backend.trip.domain.RecommendationMode;
import com.yeso.backend.trip.domain.Transport;

import java.util.List;
import java.util.Map;

/** 코스 생성기({@link CourseGenerator})의 입력·출력 타입. application 계층 타입이라 다른 기능이 그대로 쓴다. */
public final class CourseGeneration {

    private CourseGeneration() {
    }

    /**
     * 코스 생성 재료.
     *
     * @param regionName        지역 이름(예: "경주시"). 규칙 제목에 쓴다
     * @param days              여행 일수(1~7)
     * @param scheduleDensity   {@code RELAXED}(하루 4곳) | {@code PACKED}(하루 6곳)
     * @param candidates        추천 가능 관광지(attraction 모듈 판정 결과)
     * @param requesterVector   요청자 취향 벡터. 없으면 null
     * @param attractionVectors 관광지 취향 벡터(관광지 ID → 벡터). 없으면 빈 맵
     * @param officialCourses   같은 지역 TourAPI 공식 코스. 장소는 추천 가능 관광지 ID로 연결된 것만. 없으면 빈 목록
     * @param randomOnly        사용자가 완전 랜덤을 골랐으면 true. 취향·공식 코스를 쓰지 않는다
     * @param evidence          요청자 설문에서 나온 추천 이유 근거. 없으면 {@link TasteEvidence#NONE}
     */
    public record Request(
            String regionName, int days, String scheduleDensity, Transport transport,
            List<CourseCandidate> candidates, float[] requesterVector, Map<Long, float[]> attractionVectors,
            List<OfficialCourse> officialCourses, boolean randomOnly, TasteEvidence evidence) {
        public Request {
            if (evidence == null) {
                evidence = TasteEvidence.NONE;
            }
        }
    }

    /** TourAPI 공식 코스. {@code attractionIds}는 코스에 나오는 순서다. */
    public record OfficialCourse(String title, List<Long> attractionIds) {
    }

    /** 생성 결과. 날짜별 순서 목록이며 시각은 없다. */
    public record Result(RecommendationMode mode, String title, List<Day> days, List<Warning> warnings) {
    }

    public record Day(int dayIndex, List<Item> items) {
    }

    /** 코스 항목. 관광지 또는 식사. */
    public sealed interface Item permits AttractionItem, MealItem {
    }

    /**
     * @param travelMinutesFromPrevious 앞 관광지에서 오는 이동시간(분, 직선거리 추정). 그날 첫 관광지는 null
     * @param reason                    사실에 근거한 추천 이유. 근거가 없으면 null
     */
    public record AttractionItem(CourseCandidate attraction, Integer travelMinutesFromPrevious, String reason)
            implements Item {
    }

    public record MealItem(MealType mealType) implements Item {
    }

    /** {@code dayIndex}가 null이면 코스 전체에 대한 경고다. */
    public record Warning(String code, Integer dayIndex) {
    }
}
