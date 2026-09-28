package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.LikedTrip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LikedTripRepository extends JpaRepository<LikedTrip, Long> {
}
