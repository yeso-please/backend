package com.yeso.backend.trip.presentation.invite;

/**
 * 공유 링크는 항상 읽기 전용이다.
 *
 * @param expiresInDays 생략하면 7일. 허용 범위는 1~30일이다.
 */
public record CreateShareLinkRequest(Integer expiresInDays) {
}
