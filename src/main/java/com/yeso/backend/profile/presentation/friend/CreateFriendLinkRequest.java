package com.yeso.backend.profile.presentation.friend;

/**
 * @param expiresInDays 생략하면 7일. 허용 범위는 1~30일이다.
 */
public record CreateFriendLinkRequest(Integer expiresInDays) {
}
