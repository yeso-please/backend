package com.yeso.backend.trip.presentation.invite;

import java.time.LocalDate;

/** 로그인 전에 보는 초대 미리보기. 코스·참여자 이름 목록은 노출하지 않는다. */
public record InvitePublicSummaryResponse(
        boolean valid,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        String regionName,
        String inviterNickname,
        long participantCount
) {
}
