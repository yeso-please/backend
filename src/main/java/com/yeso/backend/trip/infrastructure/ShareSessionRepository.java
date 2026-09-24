package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.ShareSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShareSessionRepository extends JpaRepository<ShareSession, Long> {

    Optional<ShareSession> findBySessionTokenHash(String sessionTokenHash);
}
