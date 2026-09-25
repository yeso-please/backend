package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface TripStopRepository extends JpaRepository<TripStop, Long> {

    boolean existsByTripPlanId(Long tripPlanId);

    @Query("select distinct s.tripPlan.id from TripStop s where s.tripPlan.id in :tripIds")
    Set<Long> findTripPlanIdsWithStops(@Param("tripIds") Collection<Long> tripIds);
}
