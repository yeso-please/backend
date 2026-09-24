package com.yeso.backend.onboarding.application;

import com.yeso.backend.onboarding.domain.EmbeddingJob;
import com.yeso.backend.onboarding.domain.TasteStatus;
import com.yeso.backend.onboarding.infrastructure.EmbeddingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** PENDING이면서 next_attempt_at이 지난 job을 주기적으로 재시도한다. */
@Slf4j
@Component
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
