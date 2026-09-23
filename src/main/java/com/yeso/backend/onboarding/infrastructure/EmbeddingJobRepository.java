package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.onboarding.domain.EmbeddingJob;
import com.yeso.backend.onboarding.domain.TasteStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, Long> {

    Optional<EmbeddingJob> findBySubmissionId(UUID submissionId);

    /**
     * 동시에 여러 sweeper/리스너 실행이 같은 job을 집어 Python을 중복 호출하지 않도록 row를
     * 잠근다 — runJob() 안에서만 쓰고, 잠금을 쥔 채로 status != PENDING이면 즉시 반환한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from EmbeddingJob j where j.submission.id = :submissionId")
    Optional<EmbeddingJob> findBySubmissionIdForUpdate(UUID submissionId);

    List<EmbeddingJob> findByStatusAndNextAttemptAtLessThanEqual(TasteStatus status, LocalDateTime now);
}
