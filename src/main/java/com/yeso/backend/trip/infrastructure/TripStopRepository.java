package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripStopRepository extends JpaRepository<TripStop, Long> {

    boolean existsByTripPlanId(Long tripPlanId);
}
