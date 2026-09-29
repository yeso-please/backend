package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.domain.DiaryShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiaryShareLinkRepository extends JpaRepository<DiaryShareLink, Long> {
    Optional<DiaryShareLink> findByTokenHash(String tokenHash);
    Optional<DiaryShareLink> findByIdAndDiaryId(Long id, Long diaryId);
    List<DiaryShareLink> findByDiaryIdOrderByCreatedAtDesc(Long diaryId);
}
