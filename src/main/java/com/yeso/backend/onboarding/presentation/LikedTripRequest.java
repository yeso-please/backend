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
        // note는 trim한 길이를 기준으로 500자를 검증한다(@Size는 필드에 최종 저장된 값을 검사하므로
        // trim을 먼저 해 두지 않으면 앞뒤 공백만 있는 입력이 원문 길이 기준으로 부당하게 거부된다).
        if (note != null) {
            note = note.trim();
        }
    }
}
