package com.yeso.backend.trip.domain;

import com.yeso.backend.attraction.domain.Attraction;
import com.yeso.backend.auth.domain.User;
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

import java.time.LocalDateTime;

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

    /** 읽기 전용. 코스를 볼 때 관광지를 로드하지 않고 ID만 쓴다. */
    @Column(name = "attraction_id", insertable = false, updatable = false)
    private Long attractionId;

    /** 식사 항목만 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", length = 20)
    private MealType mealType;

    @Column(name = "stay_minutes", nullable = false)
    private int stayMinutes;

    /**
     * 바로 앞 지점(관광지 또는 고른 식당)에서 오는 이동 시간(분, 직선거리 추정). 식당 미정 식사는 건너뛰므로 null이고,
     * 그날 첫 지점도 null이다.
     */
    @Column(name = "travel_minutes_from_previous")
    private Integer travelMinutesFromPrevious;

    /** 관광지 항목만 있다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CourseItemSource source;

    /** 추천 이유. 수동으로 넣은 항목이면 null이다. */
    @Column
    private String reason;

    /** {@code reason}의 출처. 규칙 문장(RULE) 또는 ai {@code /explanations}(LLM). 이유가 없으면 null. */
    @Column(name = "reason_source", length = 10)
    private String reasonSource;

    /** AI 장소 이유 중 취향 기준 회원 본인에게만 보이는 문장(5-1 tasteBasis). */
    @Column(name = "personal_reason", length = 200)
    private String personalReason;

    // ---- 변경 기록(관광지 항목만). 기준점 대비 출처와 마지막으로 추가·교체한 사람. ----

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CourseItemOrigin origin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by_user_id")
    private User changedBy;

    /** 추가·교체한 사람의 닉네임 스냅샷. 그 사람이 탈퇴·강퇴돼도 기록에 남긴다. */
    @Column(name = "changed_by_nickname", length = 50)
    private String changedByNickname;

    /** 기준점 항목을 처음 교체했을 때 원래 장소. 여러 번 바꿔도 기준점 장소를 유지한다. */
    @Column(name = "replaced_from_attraction_id")
    private Long replacedFromAttractionId;

    @Column(name = "replaced_from_name", length = 255)
    private String replacedFromName;

    @Column(name = "change_note", length = 100)
    private String changeNote;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    /** 자동 생성(5-1)의 기준점 항목. */
    public static CourseItem attraction(
            TripPlan tripPlan, int dayIndex, int orderIndex, Attraction attraction, int stayMinutes,
            Integer travelMinutesFromPrevious, CourseItemSource source, String reason) {
        CourseItem item = new CourseItem(tripPlan, dayIndex, orderIndex, CourseItemKind.ATTRACTION, stayMinutes);
        item.attraction = attraction;
        item.attractionId = attraction.getId();
        item.travelMinutesFromPrevious = travelMinutesFromPrevious;
        item.source = source;
        item.reason = reason;
        item.reasonSource = reason == null ? null : "RULE";
        item.origin = CourseItemOrigin.BASE;
        return item;
    }

    /** 기준점 이후 사용자가 넣은 항목(5-3 ADD·RESTORE). 되살린 기준점 항목이면 {@code origin}이 BASE다. */
    public static CourseItem added(
            TripPlan tripPlan, int dayIndex, int orderIndex, Attraction attraction, int stayMinutes,
            CourseItemOrigin origin, User by, String byNickname, LocalDateTime at) {
        CourseItem item = new CourseItem(tripPlan, dayIndex, orderIndex, CourseItemKind.ATTRACTION, stayMinutes);
        item.attraction = attraction;
        item.attractionId = attraction.getId();
        item.source = CourseItemSource.MANUAL;
        item.origin = origin;
        item.changedBy = by;
        item.changedByNickname = byNickname;
        item.changedAt = at;
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

    /**
     * 장소를 바꾼다(5-3 REPLACE). 기준점 항목이면 {@code REPLACED}가 되고 원래 장소를 기억한다. 이미 교체한 항목을 또
     * 바꾸면 기준점 장소를 그대로 두고, 추가한 항목을 바꾸면 계속 {@code ADDED}다. 이유 문장은 새 장소와 맞지 않아 지운다.
     *
     * @param previousName 바꾸기 전 장소 이름(기준점 장소를 처음 바꿀 때 기록한다)
     */
    public void replaceAttraction(Attraction attraction, int stayMinutes, User by, String byNickname,
                                  String previousName, String note, LocalDateTime at) {
        if (isMeal()) {
            throw new IllegalArgumentException("식사 항목은 관광지로 교체할 수 없습니다");
        }
        if (origin == CourseItemOrigin.BASE) {
            this.origin = CourseItemOrigin.REPLACED;
            this.replacedFromAttractionId = this.attractionId;
            this.replacedFromName = previousName;
        }
        this.attraction = attraction;
        this.attractionId = attraction.getId();
        this.stayMinutes = stayMinutes;
        this.source = CourseItemSource.MANUAL;
        this.reason = null;
        this.reasonSource = null;
        this.personalReason = null;
        this.changedBy = by;
        this.changedByNickname = byNickname;
        this.changeNote = note;
        this.changedAt = at;
    }

    /** ai {@code /explanations} 결과로 이유 문장을 바꾼다(5-1 직후). {@code reason}이 null이면 규칙 문장을 둔다. */
    public void applyExplanation(String reason, String personalReason) {
        if (reason != null) {
            this.reason = reason;
            this.reasonSource = "LLM";
        }
        this.personalReason = personalReason;
    }
}
