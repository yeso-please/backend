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

        try {
            EmbeddingResult result = embeddingClient.embed(new EmbeddingRequest(
                    String.valueOf(job.getId()), job.getModelVersion(), job.getTemplateVersion(),
                    profileFor(submission, job.getTemplateVersion())));

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

    private EmbeddingRequest.Profile profileFor(OnboardingSubmission submission, int templateVersion) {
        var likedTrips = likedTripRepository.findAllBySubmission_IdOrderByIdAsc(submission.getId());
        if (templateVersion == OnboardingQuestionBank.LEGACY_TEMPLATE_VERSION) {
            return new EmbeddingRequest.Profile(
                    nullToEmpty(submission.getMbtiCode()),
                    submission.getScheduleDensity().name(),
                    submission.getExperienceTags(),
                    submission.getExcludeTags(),
                    likedTrips.stream().map(trip -> new EmbeddingRequest.LikedTrip(
                            trip.getRegion().getSigCd(), regionName(trip), trip.getTags(), nullToEmpty(trip.getNote())))
                            .toList(),
                    java.util.Map.of(), java.util.List.of(), java.util.List.of());
        }
        if (templateVersion != OnboardingQuestionBank.AIHUB_TEMPLATE_VERSION) {
            throw new IllegalArgumentException("지원하지 않는 회원 템플릿 버전입니다.");
        }
        return new EmbeddingRequest.Profile(
                "", submission.getScheduleDensity().name(), java.util.List.of(), submission.getExcludeTags(),
                java.util.List.of(), submission.getTravelStyles(), submission.getTravelMotives(),
                likedTrips.stream().map(this::regionName).toList());
    }

    private String regionName(com.yeso.backend.profile.domain.LikedTrip trip) {
        return trip.getRegion().getProvince() + " " + trip.getRegion().getCity();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
