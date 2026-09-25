package com.yeso.backend.profile.presentation.friend;

/** 로그인 전에 보는 친구 초대 링크 미리보기. 보낸 사람 닉네임만 노출한다. */
public record FriendLinkPreviewResponse(boolean valid, String inviterNickname) {
}
