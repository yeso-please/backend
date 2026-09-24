package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.onboarding.domain.LikedTrip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LikedTripRepository extends JpaRepository<LikedTrip, Long> {
}
