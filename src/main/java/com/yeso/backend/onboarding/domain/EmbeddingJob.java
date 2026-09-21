package com.yeso.backend.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 제출 1건당 임베딩 요청 상태와 재시도 bookkeeping. 성공 전까지는 user_taste_vectors/
 * guest_taste_vectors에 row가 생기지 않으므로(두 테이블 모두 embedding NOT NULL) 이 테이블이
 * "아직 준비되지 않은 상태"를 표현하는 유일한 곳이다.
 */
@Entity
@Table(name = "embedding_jobs")
@Getter
@Setter
@NoArgsConstructor
public class EmbeddingJob {

    private static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private OnboardingSubmission submission;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    private EmbeddingOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TasteStatus status = TasteStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public EmbeddingJob(
            OnboardingSubmission submission, EmbeddingOwnerType ownerType, Long ownerId,
            String modelVersion, int templateVersion
    ) {
        this.submission = submission;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.modelVersion = modelVersion;
        this.templateVersion = templateVersion;
    }

    public void markReady() {
        this.status = TasteStatus.READY;
        this.lastErrorCode = null;
        this.nextAttemptAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** @return 재시도 여력이 남아있으면 true(여전히 PENDING), 소진됐으면 false(FAILED로 전환) */
    public boolean scheduleRetryOrFail(String errorCode, LocalDateTime nextAttemptAt) {
        this.attempts += 1;
        this.lastErrorCode = errorCode;
        this.updatedAt = LocalDateTime.now();
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = TasteStatus.FAILED;
            this.nextAttemptAt = null;
            return false;
        }
        this.status = TasteStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        return true;
    }

    public void markFailedPermanently(String errorCode) {
        this.status = TasteStatus.FAILED;
        this.lastErrorCode = errorCode;
        this.nextAttemptAt = null;
        this.updatedAt = LocalDateTime.now();
    }
}
