package com.yeso.backend.profile.application.onboarding;

import com.yeso.backend.profile.domain.EmbeddingJob;
import com.yeso.backend.profile.domain.TasteStatus;
import com.yeso.backend.profile.infrastructure.EmbeddingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING이면서 next_attempt_at이 지난 job을 주기적으로 재시도한다.
 * {@code embedding.retry-sweep-enabled=false}면 bean을 만들지 않는다(테스트 profile은 끈다 — 매 테스트 후 TRUNCATE와 겹치지 않게).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "embedding", name = "retry-sweep-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddingRetrySweeper {

    private final EmbeddingJobRepository embeddingJobRepository;
    private final OnboardingEmbeddingRunner runner;

    @Scheduled(fixedDelayString = "${embedding.retry-sweep-interval-ms:30000}")
    @Transactional(readOnly = true)
    public void sweep() {
        List<EmbeddingJob> due = embeddingJobRepository.findByStatusAndNextAttemptAtLessThanEqual(
                TasteStatus.PENDING, LocalDateTime.now());
        for (EmbeddingJob job : due) {
            try {
                runner.runJob(job.getSubmission().getId());
            } catch (RuntimeException e) {
                log.error("Embedding retry sweep failed for jobId={}", job.getId(), e);
            }
        }
    }
}
