package com.yeso.backend.trip.presentation.invite;

/**
 * @param permission 이 링크로 들어온 손님이 확정 일정에 대해 가질 기본 권한. 생략하면 VIEW다.
 * @param expiresInDays 생략하면 7일. 허용 범위는 1~30일이다.
 */
public record CreateInviteRequest(String permission, Integer expiresInDays) {
}
