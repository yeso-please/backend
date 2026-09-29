package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.DiaryShareSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiaryShareSessionRepository extends JpaRepository<DiaryShareSession, Long> {
    Optional<DiaryShareSession> findBySessionTokenHash(String tokenHash);
}
