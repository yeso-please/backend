package com.yeso.backend.trip.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenAudienceTest {

    @Nested
    @DisplayName("용도 접두사")
    class Prefix {

        @Test
        @DisplayName("자기 용도로 만든 token만 인정한다")
        void matchesOwnAudienceOnly() {
            String inviteToken = TokenAudience.INVITE.decorate("abc");

            assertThat(TokenAudience.INVITE.matches(inviteToken)).isTrue();
            assertThat(TokenAudience.SHARE_LINK.matches(inviteToken)).isFalse();
            assertThat(TokenAudience.SHARE_SESSION.matches(inviteToken)).isFalse();
        }

        @Test
        @DisplayName("접두사가 없거나 null이면 어떤 용도에도 맞지 않는다")
        void rejectsUndecoratedTokens() {
            assertThat(TokenAudience.INVITE.matches("abc")).isFalse();
            assertThat(TokenAudience.INVITE.matches(null)).isFalse();
        }

        /** 접두사가 구분자 없이 겹쳐 다른 용도로 통과하면 안 된다(예: "ss"로 시작하는 본문). */
        @Test
        @DisplayName("구분자까지 일치해야 한다")
        void requiresSeparator() {
            assertThat(TokenAudience.SHARE_SESSION.matches("sside-effect")).isFalse();
            assertThat(TokenAudience.SHARE_SESSION.matches("ss_body")).isTrue();
        }
    }
}
