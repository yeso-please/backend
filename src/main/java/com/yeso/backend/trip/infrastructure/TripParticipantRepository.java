package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripParticipantRepository extends JpaRepository<TripParticipant, Long> {
}
