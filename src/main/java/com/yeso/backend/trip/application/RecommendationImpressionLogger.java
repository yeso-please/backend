package com.yeso.backend.trip.application;

import com.yeso.backend.trip.infrastructure.RecommendationImpressionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * 추천 노출 기록(docs/design/recommendation.md §9). 지역 뽑기·코스 생성의 후보와 뽑힐 확률을 남긴다.
 *
 * <p>요청이 커밋된 뒤 별도 트랜잭션으로 쓴다. 코스 생성은 여행 행을 잠그므로 같은 트랜잭션 안에서 새 트랜잭션을 열면
 * 외래 키 검사가 그 잠금을 기다린다. 기록 실패는 사용자 요청을 실패시키지 않는다(경고 로그만).
 */
@Slf4j
@Component
public class RecommendationImpressionLogger {

    public static final String REGION_DRAW = "REGION_DRAW";
    public static final String REGION_PRE_DRAW = "REGION_PRE_DRAW";
    public static final String COURSE_GENERATION = "COURSE_GENERATION";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RecommendationImpressionRepository repository;
    private final TransactionTemplate newTransaction;
    private final String modelVersion;
    private final int templateVersion;

    public RecommendationImpressionLogger(
            RecommendationImpressionRepository repository, PlatformTransactionManager transactionManager,
            @Value("${embedding.model-version:}") String modelVersion,
            @Value("${embedding.profile-template-version:0}") int templateVersion) {
        this.repository = repository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.modelVersion = modelVersion;
        this.templateVersion = templateVersion;
    }

    /** {@code id}는 지역 뽑기면 sigCd, 코스면 관광지 ID. {@code score}는 취향 점수(없으면 null). */
    public record Candidate(Object id, Double score, double weight, double probability) {
    }

    /** {@code step}은 뽑은 순서(0부터), {@code probability}는 그 단계에서 남은 후보 중 이것이 뽑힐 확률. */
    public record Chosen(Object id, int step, double probability) {
    }

    public void record(Long userId, Long tripPlanId, String stage, String policy, List<String> appliedConditions,
                       List<Candidate> candidates, List<Chosen> chosen) {
        UUID requestId = UUID.randomUUID();
        Runnable write = () -> {
            try {
                String applied = JSON.writeValueAsString(appliedConditions);
                String candidatesJson = JSON.writeValueAsString(candidates);
                String chosenJson = JSON.writeValueAsString(chosen);
                newTransaction.executeWithoutResult(status -> repository.insert(requestId, userId, tripPlanId, stage, policy,
                        applied, modelVersion, templateVersion, candidatesJson, chosenJson));
            } catch (RuntimeException e) {
                log.warn("recommendation impression not recorded stage={} requestId={}: {}", stage, requestId, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }
}
