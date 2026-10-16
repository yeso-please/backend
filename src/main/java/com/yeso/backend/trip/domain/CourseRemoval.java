package com.yeso.backend.trip.domain;

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
 * 기준점 이후 코스에서 지운 관광지(docs/api/trip.md 5장 "변경 기록"). 다시 넣을 수 있다(5-3 RESTORE).
 * 재생성하면 새 기준점이 되어 모두 지운다. 이름은 스냅샷이라 관광지가 사라져도 기록이 남는다.
 */
@Entity
@Table(name = "course_removals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseRemoval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_plan_id", nullable = false)
    private Long tripPlanId;

    @Column(name = "attraction_id")
    private Long attractionId;

    @Column(name = "attraction_name", nullable = false, length = 255)
    private String attractionName;

    @Column(name = "day_index", nullable = false)
    private int dayIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseItemOrigin origin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by_user_id")
    private User removedBy;

    @Column(name = "removed_by_nickname", length = 50)
    private String removedByNickname;

    @Column(length = 100)
    private String note;

    @Column(name = "removed_at", nullable = false)
    private LocalDateTime removedAt;

    @Column(name = "restored_at")
    private LocalDateTime restoredAt;

    public CourseRemoval(Long tripPlanId, Long attractionId, String attractionName, int dayIndex, CourseItemOrigin origin,
                         User removedBy, String removedByNickname, String note, LocalDateTime removedAt) {
        this.tripPlanId = tripPlanId;
        this.attractionId = attractionId;
        this.attractionName = attractionName;
        this.dayIndex = dayIndex;
        this.origin = origin;
        this.removedBy = removedBy;
        this.removedByNickname = removedByNickname;
        this.note = note;
        this.removedAt = removedAt;
    }

    public boolean isRestored() {
        return restoredAt != null;
    }

    public void markRestored(LocalDateTime at) {
        this.restoredAt = at;
    }
}
