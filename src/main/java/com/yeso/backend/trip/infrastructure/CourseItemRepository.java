package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.CourseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface CourseItemRepository extends JpaRepository<CourseItem, Long> {

    boolean existsByTripPlanId(Long tripPlanId);

    /** 날짜 → 순서로 정렬한 코스 전체. */
    List<CourseItem> findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(Long tripPlanId);

    @Query("select distinct i.tripPlan.id from CourseItem i where i.tripPlan.id in :tripIds")
    Set<Long> findTripPlanIdsWithItems(@Param("tripIds") Collection<Long> tripIds);

    /**
     * 고른 식당(course_meal_restaurants)은 DB의 ON DELETE CASCADE로 함께 지워진다.
     * 영속성 컨텍스트를 비우지 않는다(clearAutomatically 금지) — 비우면 호출한 쪽이 들고 있는 TripPlan이
     * 분리돼 이후 변경(코스 정보 비우기, 지역 변경)이 저장되지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from CourseItem i where i.tripPlan.id = :tripPlanId")
    int deleteByTripPlanId(@Param("tripPlanId") Long tripPlanId);
}
