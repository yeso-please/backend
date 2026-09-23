package com.yeso.backend.onboarding.domain;

import com.yeso.backend.shared.exception.ErrorCode;

/** 클라이언트가 보낸 questionVersion이 현재 서버 상수와 다르다 — 클라이언트는 질문을 다시 받아야 한다. */
public class InvalidQuestionVersionException extends OnboardingException {
    public InvalidQuestionVersionException() {
        super(ErrorCode.INVALID_QUESTION_VERSION,
                "질문 버전이 최신이 아닙니다. GET /api/onboarding/questions로 다시 받아주세요.");
    }
}
