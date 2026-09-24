package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TripPlanRepository extends JpaRepository<TripPlan, Long> {

    List<TripPlan> findByOwnerUserIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long ownerUserId, TripPlanStatus status, LocalDate endDateAtMost, LocalDate startDateAtLeast);

    List<TripPlan> findByOwnerUserIdAndStatus(Long ownerUserId, TripPlanStatus status);
}
