package com.yeso.backend.profile.application.onboarding;

import com.yeso.backend.auth.infrastructure.UserRepository;
import com.yeso.backend.profile.domain.UserTasteVector;
import com.yeso.backend.profile.domain.EmbeddingJob;
import com.yeso.backend.profile.domain.OnboardingSubmission;
import com.yeso.backend.profile.domain.OnboardingQuestionBank;
import com.yeso.backend.profile.domain.TasteStatus;
import com.yeso.backend.profile.infrastructure.EmbeddingClient;
import com.yeso.backend.profile.infrastructure.EmbeddingJobRepository;
import com.yeso.backend.profile.infrastructure.EmbeddingPermanentException;
import com.yeso.backend.profile.infrastructure.EmbeddingProperties;
import com.yeso.backend.profile.infrastructure.EmbeddingRequest;
import com.yeso.backend.profile.infrastructure.EmbeddingResult;
import com.yeso.backend.profile.infrastructure.EmbeddingTransientException;
import com.yeso.backend.profile.infrastructure.LikedTripRepository;
import com.yeso.backend.profile.infrastructure.UserTasteVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * 실제 임베딩 호출과 job/submission 상태 갱신. {@link OnboardingSubmittedEventListener}와
 * {@link EmbeddingRetrySweeper}가 이 bean을 외부에서 호출한다 — 같은 클래스 안에서
 * {@code this.runJob(...)}으로 자기 호출하면 Spring AOP 프록시를 거치지 않아
 * {@code @Transactional(REQUIRES_NEW)}가 조용히 무시된다(자기 호출 한계).
 * 이 클래스 안에서 던지는 예외는 모두 잡아 job/submission 상태로 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingEmbeddingRunner {

    private final EmbeddingJobRepository embeddingJobRepository;
    private final UserTasteVectorRepository userTasteVectorRepository;
    private final UserRepository userRepository;
    private final LikedTripRepository likedTripRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runJob(UUID submissionId) {
        EmbeddingJob job = embeddingJobRepository.findBySubmissionIdForUpdate(submissionId).orElse(null);
        if (job == null) {
            log.warn("No embedding job for submission (already processed or missing): submissionId={}", submissionId);
            return;
        }
        if (job.getStatus() != TasteStatus.PENDING) {
            return;
        }
        OnboardingSubmission submission = job.getSubmission();

        if (job.getTemplateVersion() != OnboardingQuestionBank.AIHUB_TEMPLATE_VERSION) {
            // 구형 설문(템플릿 1)은 폐기했다. 남은 job은 다시 시도하지 않게 영구 실패로 끝낸다.
            failPermanently(job, submission, "UNSUPPORTED_TEMPLATE_VERSION");
            return;
        }

        try {
            EmbeddingResult result = embeddingClient.embed(new EmbeddingRequest(
                    String.valueOf(job.getId()), job.getModelVersion(), job.getTemplateVersion(),
                    profileFor(submission)));

            if (result.dimension() != embeddingProperties.getExpectedDimension()) {
                failPermanently(job, submission, "EMBEDDING_DIMENSION_MISMATCH");
                return;
            }
            applyReady(job, submission, result);
        } catch (EmbeddingTransientException e) {
            retryOrFail(job, submission, e.errorCode());
        } catch (EmbeddingPermanentException e) {
            failPermanently(job, submission, e.errorCode());
        } catch (RuntimeException e) {
            log.error("Unexpected error while running embedding job jobId={}", job.getId(), e);
            failPermanently(job, submission, "UNEXPECTED_ERROR");
        }
    }

    private EmbeddingRequest.Profile profileFor(OnboardingSubmission submission) {
        var likedTrips = likedTripRepository.findAllBySubmission_IdOrderByIdAsc(submission.getId());
        // MBTI는 표시용이라 보내지 않는다(docs/design/recommendation.md 5절).
        return new EmbeddingRequest.Profile(
                submission.getScheduleDensity().name(), submission.getExcludeTags(),
                submission.getTravelStyles(), submission.getTravelMotives(),
                likedTrips.stream().map(trip -> trip.getRegion().displayName()).toList());
    }

    private void applyReady(EmbeddingJob job, OnboardingSubmission submission, EmbeddingResult result) {
        byte[] embedding = Base64.getDecoder().decode(result.embeddingBase64());
        // submission.getUser()는 다른(이미 끝난) 트랜잭션의 lazy proxy이므로 이 세션에서
        // 새로 참조를 얻는다. UserTasteVector가 Persistable을 구현해 save()가 새 row에는
        // persist를, 기존 row에는 merge를 정확히 선택한다(@MapsId + 비생성 ID라 기본 판정이 틀림).
        UserTasteVector vector = userTasteVectorRepository.findById(job.getOwnerId())
                .orElseGet(() -> new UserTasteVector(
                        userRepository.getReferenceById(job.getOwnerId()), embedding, result.dimension(),
                        submission.getProfileText(), job.getModelVersion(), job.getTemplateVersion()));
        vector.setEmbedding(embedding);
        vector.setDimension(result.dimension());
        vector.setProfileText(submission.getProfileText());
        vector.setModelVersion(job.getModelVersion());
        vector.setTemplateVersion(job.getTemplateVersion());
        vector.setUpdatedAt(LocalDateTime.now());
        userTasteVectorRepository.save(vector);
        job.markReady();
        submission.setTasteStatus(TasteStatus.READY);
    }

    private void retryOrFail(EmbeddingJob job, OnboardingSubmission submission, String errorCode) {
        LocalDateTime nextAttemptAt = LocalDateTime.now()
                .plusSeconds((long) embeddingProperties.getRetryBackoffSeconds() * (job.getAttempts() + 1));
        boolean willRetry = job.scheduleRetryOrFail(errorCode, nextAttemptAt, embeddingProperties.getMaxAttempts());
        submission.setTasteStatus(willRetry ? TasteStatus.PENDING : TasteStatus.FAILED);
    }

    private void failPermanently(EmbeddingJob job, OnboardingSubmission submission, String errorCode) {
        job.markFailedPermanently(errorCode);
        submission.setTasteStatus(TasteStatus.FAILED);
    }
}
