package com.yeso.backend.auth.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KakaoLoginRequest(
        @NotBlank(message = "인가 코드를 입력해주세요.")
        @Size(max = 512, message = "인가 코드는 512자를 넘을 수 없습니다.")
        String code,

        @NotBlank(message = "redirectUri를 입력해주세요.")
        String redirectUri
) {
}
