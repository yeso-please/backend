package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.CourseRemoval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CourseRemovalRepository extends JpaRepository<CourseRemoval, Long> {

    /** 아직 되살리지 않은 제외 기록, 최근 것부터. */
    List<CourseRemoval> findByTripPlanIdAndRestoredAtIsNullOrderByRemovedAtDescIdDesc(Long tripPlanId);

    /** 재생성·코스 비우기 때 새 기준점이 되므로 기록을 지운다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from CourseRemoval r where r.tripPlanId = :tripPlanId")
    int deleteByTripPlanId(@Param("tripPlanId") Long tripPlanId);
}
