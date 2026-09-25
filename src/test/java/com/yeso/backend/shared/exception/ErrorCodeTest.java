package com.yeso.backend.shared.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    @DisplayName("코드 문자열은 서로 겹치지 않는다")
    void codes_areUnique() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("코드 문자열은 대문자 스네이크 케이스다")
    void codes_areUpperSnakeCase() {
        assertThat(ErrorCode.values())
                .allSatisfy(errorCode -> assertThat(errorCode.code()).matches("[A-Z][A-Z0-9]*(_[A-Z0-9]+)+"));
    }
}
