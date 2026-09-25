package com.yeso.backend.profile.domain;

/**
 * 여행 MBTI 4축. 동점 시 뒤 글자(second)를 채택한다(docs/api/profile.md 부록).
 * 최종 코드는 {@link #EI}+{@link #SN}+{@link #TF}+{@link #JP} 순서로 합성한다.
 */
public enum OnboardingAxis {
    EI('E', 'I'),
    SN('S', 'N'),
    TF('T', 'F'),
    JP('J', 'P');

    private final char first;
    private final char second;

    OnboardingAxis(char first, char second) {
        this.first = first;
        this.second = second;
    }

    public char first() {
        return first;
    }

    public char second() {
        return second;
    }

    /** 동점일 때 채택하는 글자 — 두 번째로 표기된 글자(I/N/F/P)다. */
    public char tieBreakLetter() {
        return second;
    }
}
