package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.CourseShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseShareLinkRepository extends JpaRepository<CourseShareLink, Long> {

    Optional<CourseShareLink> findByTokenHash(String tokenHash);

    List<CourseShareLink> findByTripPlanIdOrderByCreatedAtDesc(Long tripPlanId);
}
