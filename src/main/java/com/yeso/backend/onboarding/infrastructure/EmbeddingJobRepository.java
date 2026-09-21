package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.onboarding.domain.EmbeddingJob;
import com.yeso.backend.onboarding.domain.TasteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, Long> {

    Optional<EmbeddingJob> findBySubmissionId(UUID submissionId);

    List<EmbeddingJob> findByStatusAndNextAttemptAtLessThanEqual(TasteStatus status, LocalDateTime now);
}
