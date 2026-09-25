package com.yeso.backend.profile.application.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 제출 트랜잭션이 커밋된 뒤에만 {@link OnboardingEmbeddingRunner}를 외부 bean 호출로 실행한다
 * (AFTER_COMMIT) — 임베딩 호출 실패가 이미 커밋된 submission을 되돌리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OnboardingSubmittedEventListener {

    private final OnboardingEmbeddingRunner runner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(OnboardingSubmittedEvent event) {
        runner.runJob(event.submissionId());
    }
}
