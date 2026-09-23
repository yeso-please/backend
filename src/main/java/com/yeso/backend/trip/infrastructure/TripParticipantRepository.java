package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripParticipantRepository extends JpaRepository<TripParticipant, Long> {

    List<TripParticipant> findByTripPlanId(Long tripPlanId);
}
