package com.yeso.backend.onboarding.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MbtiScorerTest {

    /** 1~12번 모두 같은 choice로 채워진 기본 맵을 만들고 필요한 문항만 덮어쓴다. */
    private static Map<Integer, Integer> answers(int defaultChoice, Map<Integer, Integer> overrides) {
        Map<Integer, Integer> answers = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            answers.put(i, defaultChoice);
        }
        answers.putAll(overrides);
        return answers;
    }

    @Test
    @DisplayName("모든 문항 choice=1이면 EI=E, SN=S, TF=F, JP=P가 우세해 ESFP가 된다")
    void score_allChoiceOne_producesExpectedCode() {
        // choice=1 letters: EI-questions(10,11,12)->E, SN(5,6)->S, TF(7,8,9)->F, JP(1,2,3)->P, JP(4)->J
        // JP: 3xP + 1xJ -> P 우세
        Map<Integer, Integer> allOne = answers(1, Map.of());

        assertThat(MbtiScorer.score(allOne)).isEqualTo("ESFP");
    }

    @Test
    @DisplayName("모든 문항 choice=2이면 EI=I, SN=N, TF=T, JP는 3xJ+1xP로 J가 우세해 INTJ가 된다")
    void score_allChoiceTwo_producesExpectedCode() {
        Map<Integer, Integer> allTwo = answers(2, Map.of());

        assertThat(MbtiScorer.score(allTwo)).isEqualTo("INTJ");
    }

    @Test
    @DisplayName("JP축이 2:2로 동점이면 뒤 글자 P를 채택한다")
    void score_jpAxisTie_choosesP() {
        // JP 문항: 1~3은 choice1->P/choice2->J, 4번은 반대(choice1->J/choice2->P)다.
        // 1,2번 choice1(P) + 3번 choice2(J) + 4번 choice1(J) => P:2, J:2 동점.
        Map<Integer, Integer> overrides = Map.of(1, 1, 2, 1, 3, 2, 4, 1);
        Map<Integer, Integer> tie = answers(1, overrides);

        String code = MbtiScorer.score(tie);

        assertThat(code).endsWith("P");
    }

    @Test
    @DisplayName("SN축이 동점이면 뒤 글자 N을 채택한다")
    void score_snAxisTie_choosesN() {
        // EI 문항 10,11,12는 choice1->E, choice2->I. 3문항이라 동점(1.5:1.5)이 불가능하므로
        // 대신 명시적으로 1승1패 상태를 만들 수 없다 — 홀수 문항이라 동점 시나리오는 짝수 축에서만 검증한다.
        // SN축(5,6) 2문항으로 동점을 만든다: choice1->S, choice2->N.
        Map<Integer, Integer> overrides = Map.of(5, 1, 6, 2);
        Map<Integer, Integer> tie = answers(1, overrides);

        String code = MbtiScorer.score(tie);

        assertThat(code.charAt(1)).isEqualTo('N');
    }
}
