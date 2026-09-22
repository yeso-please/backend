package com.yeso.backend.auth.presentation;

import com.yeso.backend.auth.presentation.validation.Utf8MaxByteSize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 190, message = "이메일은 190자를 넘을 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
        @Utf8MaxByteSize(max = 72, message = "비밀번호는 UTF-8 기준 72바이트를 넘을 수 없습니다.")
        String password,

        @NotBlank(message = "닉네임을 입력해주세요.")
        @Size(min = 1, max = 30, message = "닉네임은 1자 이상 30자 이하여야 합니다.")
        String nickname
) {
}
