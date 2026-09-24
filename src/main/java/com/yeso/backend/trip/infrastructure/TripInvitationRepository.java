package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.TripInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripInvitationRepository extends JpaRepository<TripInvitation, Long> {

    Optional<TripInvitation> findByTokenHash(String tokenHash);

    List<TripInvitation> findByTripPlanIdOrderByCreatedAtDesc(Long tripPlanId);
}
