package com.yeso.backend.onboarding.presentation;

import jakarta.validation.constraints.NotNull;

public record AnswerRequest(
        @NotNull(message = "questionNumber를 입력해주세요.") Integer questionNumber,
        @NotNull(message = "choice를 입력해주세요.") Integer choice
) {
}
