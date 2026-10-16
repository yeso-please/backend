package com.yeso.backend.trip.infrastructure;

import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Leg;
import com.yeso.backend.trip.infrastructure.KakaoMobilityClient.Point;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 길찾기 구간 결과를 서버 메모리에 잠깐 기억한다(docs/api/trip.md 5-7). 키는 출발·도착 좌표라서, 편집으로 바뀌지 않은
 * 구간이나 다른 참여자가 같은 코스를 다시 열 때는 카카오를 부르지 않는다. DB에 저장하지 않는다 — 서버를 다시 띄우면 비고,
 * 서버가 여러 대면 각자 따로 기억한다.
 */
@Component
public class RouteLegCache {

    private record Entry(Leg leg, Instant expiresAt) {
    }

    private final Map<String, Entry> entries;
    private final Duration ttl;
    private final Clock clock;

    public RouteLegCache(RouteProperties properties, Clock clock) {
        int maxEntries = properties.getCacheMaxEntries();
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
        this.ttl = Duration.ofMinutes(properties.getCacheTtlMinutes());
        this.clock = clock;
    }

    /** 좌표를 소수 6자리(약 0.1m)로 맞춘 구간 키. */
    public static String key(Point from, Point to) {
        return String.format(Locale.ROOT, "%.6f,%.6f>%.6f,%.6f", from.lat(), from.lng(), to.lat(), to.lng());
    }

    public synchronized Optional<Leg> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.leg());
    }

    public synchronized void put(String key, Leg leg) {
        entries.put(key, new Entry(leg, clock.instant().plus(ttl)));
    }

    /** 테스트가 매번 빈 상태로 시작하도록 비운다. */
    public synchronized void clear() {
        entries.clear();
    }
}
