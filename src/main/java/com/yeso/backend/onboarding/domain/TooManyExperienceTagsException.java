package com.yeso.backend.onboarding.domain;

import com.yeso.backend.shared.exception.ErrorCode;

public class TooManyExperienceTagsException extends OnboardingException {
    public TooManyExperienceTagsException() {
        super(ErrorCode.TOO_MANY_EXPERIENCE_TAGS,
                "경험 태그는 최대 " + OnboardingQuestionBank.MAX_EXPERIENCE_TAGS + "개까지 선택할 수 있습니다.");
    }
}
