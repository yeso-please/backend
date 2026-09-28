package com.yeso.backend.profile.domain;

/** 코스를 만들 때 취향을 반영할지. 기본은 취향 반영이다(docs/api/profile.md 2-11). */
public enum CourseTasteMode {
    /** 취향이 반영된 랜덤. 취향 데이터가 없으면 공식 코스·규칙 코스로 대신한다. */
    TASTE,
    /** 취향을 쓰지 않는 완전 랜덤. */
    RANDOM
}
