package com.yeso.backend.trip.presentation.context;

import java.time.LocalDateTime;
import java.util.List;

/** 3-12 접속 표시. 프론트는 {@code heartbeatSeconds}마다 3-11을 보내고 같은 주기로 이 목록을 다시 받는다. */
public record PresenceResponse(int heartbeatSeconds, int activeWindowSeconds, List<Active> participants) {

    public record Active(Long userId, String nickname, LocalDateTime lastSeenAt) {
    }
}
