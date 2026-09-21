package com.yeso.backend.onboarding.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record LikedTripRequest(
        @NotBlank(message = "sigCd를 입력해주세요.") String sigCd,
        @Size(max = 500, message = "note는 500자를 넘을 수 없습니다.") String note,
        List<String> tags
) {
    public LikedTripRequest {
        if (tags == null) {
            tags = List.of();
        }
    }
}
