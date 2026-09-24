package com.yeso.backend.profile.application;

import java.util.UUID;

/** 제출 트랜잭션이 커밋된 뒤에만 발행된다(AFTER_COMMIT) — 임베딩 호출을 제출 트랜잭션과 분리하기 위함. */
public record OnboardingSubmittedEvent(UUID submissionId) {
}
