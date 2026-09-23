package com.yeso.backend.trip.presentation.invite;

import java.time.LocalDate;

/** 초대 손님이 참여 전에 보는 최소 요약. 초대장 소유자/여행 내부 정보는 노출하지 않는다. */
public record InvitePublicSummaryResponse(
        boolean valid, LocalDate startDate, LocalDate endDate, String ownerNickname
) {
}
