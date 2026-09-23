package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.GuestSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuestSessionRepository extends JpaRepository<GuestSession, Long> {

    Optional<GuestSession> findByTokenHash(String tokenHash);
}
