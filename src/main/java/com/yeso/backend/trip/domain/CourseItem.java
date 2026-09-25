package com.yeso.backend.trip.domain;

import com.yeso.backend.attraction.domain.Attraction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 코스의 한 항목. 코스는 시각 없는 날짜별 순서 목록이고(docs/api/trip.md 5장), 관광지와 식사가
 * 한 목록에 섞인다. {@code dayIndex} 안에서 {@code orderIndex} 순서가 곧 방문 순서다.
 * 체류·이동 시간은 참고용 추정치다. 식사에 고른 식당은 {@link CourseMealRestaurant}에 따로 둔다.
 */
@Entity
@Table(name = "course_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseItem {

    /** 식사 항목의 참고용 체류 시간(분). */
    public static final int MEAL_STAY_MINUTES = 60;

    private static final String ATTRACTION_ID_PREFIX = "a-";
    private static final String MEAL_ID_PREFIX = "m-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_plan_id", nullable = false)
    private TripPlan tripPlan;

    @Column(name = "day_index", nullable = false)
    private int dayIndex;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseItemKind kind;

    /** 관광지 항목만 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attraction_id")
    private Attraction attraction;

    /** 식사 항목만 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", length = 20)
    private MealType mealType;

    @Column(name = "stay_minutes", nullable = false)
    private int stayMinutes;

    /** 앞 관광지에서 오는 이동 시간(분, 직선거리 추정). 그날 첫 관광지와 식사 항목은 null이다. */
    @Column(name = "travel_minutes_from_previous")
    private Integer travelMinutesFromPrevious;

    /** 관광지 항목만 있다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CourseItemSource source;

    /** 추천 이유. 수동으로 넣은 항목이면 null이다. */
    @Column
    private String reason;

    public static CourseItem attraction(
            TripPlan tripPlan, int dayIndex, int orderIndex, Attraction attraction, int stayMinutes,
            Integer travelMinutesFromPrevious, CourseItemSource source, String reason) {
        CourseItem item = new CourseItem(tripPlan, dayIndex, orderIndex, CourseItemKind.ATTRACTION, stayMinutes);
        item.attraction = attraction;
        item.travelMinutesFromPrevious = travelMinutesFromPrevious;
        item.source = source;
        item.reason = reason;
        return item;
    }

    public static CourseItem meal(TripPlan tripPlan, int dayIndex, int orderIndex, MealType mealType) {
        CourseItem item = new CourseItem(tripPlan, dayIndex, orderIndex, CourseItemKind.MEAL, MEAL_STAY_MINUTES);
        item.mealType = mealType;
        return item;
    }

    private CourseItem(TripPlan tripPlan, int dayIndex, int orderIndex, CourseItemKind kind, int stayMinutes) {
        this.tripPlan = tripPlan;
        this.dayIndex = dayIndex;
        this.orderIndex = orderIndex;
        this.kind = kind;
        this.stayMinutes = stayMinutes;
    }

    public boolean isMeal() {
        return kind == CourseItemKind.MEAL;
    }

    /** API에서 쓰는 항목 ID. 관광지 {@code a-{id}}, 식사 {@code m-{id}}. */
    public String itemId() {
        return (isMeal() ? MEAL_ID_PREFIX : ATTRACTION_ID_PREFIX) + id;
    }

    /**
     * 순서를 옮긴다. 같은 날 안의 번호 겹침은 커밋 시점에 검사한다(uq_course_item_order DEFERRABLE).
     * 식사는 같은 날 안에서만 옮긴다(날마다 점심·저녁 하나씩, uq_course_item_meal_per_day).
     */
    public void moveTo(int dayIndex, int orderIndex) {
        if (isMeal() && dayIndex != this.dayIndex) {
            throw new IllegalArgumentException("식사는 다른 날로 옮길 수 없습니다: " + id);
        }
        this.dayIndex = dayIndex;
        this.orderIndex = orderIndex;
    }

    public void updateTravelMinutesFromPrevious(Integer minutes) {
        this.travelMinutesFromPrevious = minutes;
    }
}
