package com.yeso.backend.trip.application.context;

import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.presentation.context.PresenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3-11·3-12 "보고 있어요" 접속 표시(D12). 프론트가 여행 화면을 연 동안 {@link #HEARTBEAT_SECONDS}마다 신호를 보내고,
 * 최근 {@link #ACTIVE_WINDOW_SECONDS}초 안에 신호를 보낸 참여자를 "보고 있다"고 본다.
 *
 * <p>서버 메모리에만 둔다(서버 재시작 시 사라져도 다음 신호로 복구된다). 서버를 여러 대로 늘리면 인스턴스마다 따로 기억하므로
 * 공유 저장소(예: Redis)로 옮겨야 한다. 현재 배포는 서버 한 대다.
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    public static final int HEARTBEAT_SECONDS = 20;
    public static final int ACTIVE_WINDOW_SECONDS = 60;

    private final TripService tripService;
    private final TripParticipantRepository tripParticipantRepository;
    private final Clock clock;

    /** 여행 ID → (회원 ID → 마지막 신호 시각). */
    private final Map<Long, Map<Long, Instant>> lastSeen = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public void heartbeat(Long userId, Long tripId) {
        tripService.requireParticipantTrip(userId, tripId);
        lastSeen.computeIfAbsent(tripId, id -> new ConcurrentHashMap<>()).put(userId, clock.instant());
    }

    /** 최근 60초 안에 신호를 보낸 참여자(나 포함). 나간 참여자는 빠진다. */
    @Transactional(readOnly = true)
    public PresenceResponse active(Long userId, Long tripId) {
        tripService.requireParticipantTrip(userId, tripId);
        Instant threshold = clock.instant().minus(Duration.ofSeconds(ACTIVE_WINDOW_SECONDS));
        Map<Long, Instant> seen = lastSeen.getOrDefault(tripId, Map.of());
        seen.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        List<TripParticipant> participants = tripParticipantRepository.findByTripPlanIdOrderByCreatedAtAsc(tripId);
        ZoneId zone = clock.getZone();
        List<PresenceResponse.Active> active = participants.stream()
                .filter(participant -> seen.containsKey(participant.getUser().getId()))
                .map(participant -> new PresenceResponse.Active(participant.getUser().getId(), participant.getUser().getNickname(),
                        LocalDateTime.ofInstant(seen.get(participant.getUser().getId()), zone)))
                .toList();
        return new PresenceResponse(HEARTBEAT_SECONDS, ACTIVE_WINDOW_SECONDS, active);
    }
}
