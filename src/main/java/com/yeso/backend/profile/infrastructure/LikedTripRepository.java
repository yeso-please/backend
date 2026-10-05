package com.yeso.backend.profile.infrastructure;

import com.yeso.backend.profile.domain.LikedTrip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LikedTripRepository extends JpaRepository<LikedTrip, Long> {

    List<LikedTrip> findAllBySubmission_IdOrderByIdAsc(UUID submissionId);
}
