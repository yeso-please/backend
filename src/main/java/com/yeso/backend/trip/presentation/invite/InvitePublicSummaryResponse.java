package com.yeso.backend.trip.presentation.invite;

import java.time.LocalDate;
import java.util.List;

/**
 * 로그인 전에 보는 초대 미리보기(4-4). 참여자 닉네임과 지역 대표 사진을 보여 준다(2026-10-16 D8). 설문 답·MBTI는 없다.
 * 코스는 4-16 둘러보기로 본다.
 */
public record InvitePublicSummaryResponse(
        boolean valid,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        String regionSigCd,
        String regionName,
        String heroImageUrl,
        String inviterNickname,
        long participantCount,
        List<Participant> participants,
        boolean hasCourse
) {
    public record Participant(String nickname) {
    }
}
