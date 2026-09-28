package com.yeso.backend.profile.domain;

/**
 * 제출 자체의 생애주기 상태. 현재는 원자적으로 완성된 제출만 저장하므로 {@link #SUBMITTED} 하나뿐이며,
 * 취향 임베딩 준비 여부는 이 값이 아니라 {@link TasteStatus}가 따로 나타낸다.
 */
public enum SubmissionStatus {
    SUBMITTED
}
