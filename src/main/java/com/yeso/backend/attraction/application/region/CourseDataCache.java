package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 코스 생성·교체 후보(5-1·5-4)가 요청마다 다시 읽던 지역 후보 목록과 관광지 취향 벡터를 서버 메모리에 잠깐 기억한다.
 * 둘 다 적재·검증 배치가 돌 때만 바뀌는 데이터라, 바뀐 내용은 기억 시간이 지나면 반영된다. 편집(5-3)에서 고른 관광지가
 * 추천 가능한지는 이 캐시를 쓰지 않고 그때 DB에서 확인한다({@link RegionEligibilityService#findCourseCandidate}).
 * DB에 저장하지 않는다 — 서버를 다시 띄우면 비고, 서버가 여러 대면 각자 따로 기억한다.
 */
@Component
public class CourseDataCache {

    /** 벡터가 없는 관광지를 기억해 두는 표시. 같은 관광지를 매번 다시 조회하지 않는다. */
    private static final float[] NO_VECTOR = new float[0];

    private record Timed<T>(T value, Instant expiresAt) {
    }

    private final Map<String, Timed<List<CourseCandidate>>> candidates = new ConcurrentHashMap<>();
    private final Map<Long, Timed<float[]>> vectors = new ConcurrentHashMap<>();
    private final Duration candidatesTtl;
    private final Duration vectorsTtl;
    private final Clock clock;

    public CourseDataCache(
            @Value("${course.cache.candidates-ttl-seconds:300}") long candidatesTtlSeconds,
            @Value("${course.cache.vectors-ttl-seconds:600}") long vectorsTtlSeconds,
            Clock clock) {
        this.candidatesTtl = Duration.ofSeconds(candidatesTtlSeconds);
        this.vectorsTtl = Duration.ofSeconds(vectorsTtlSeconds);
        this.clock = clock;
    }

    /** 지역 후보. 기억한 게 없거나 오래됐으면 {@code loader}로 읽어 기억한다. 읽다 실패하면 기억하지 않는다. */
    List<CourseCandidate> candidates(String sigCd, Supplier<List<CourseCandidate>> loader) {
        Instant now = clock.instant();
        Timed<List<CourseCandidate>> cached = candidates.get(sigCd);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        List<CourseCandidate> loaded = List.copyOf(loader.get());
        candidates.put(sigCd, new Timed<>(loaded, now.plus(candidatesTtl)));
        return loaded;
    }

    /** 관광지 벡터. 기억하지 않은 관광지만 {@code loader}로 읽는다. 벡터가 없는 관광지는 결과에 없다. */
    Map<Long, float[]> vectors(Collection<Long> attractionIds, Function<Collection<Long>, Map<Long, float[]>> loader) {
        Instant now = clock.instant();
        Map<Long, float[]> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (Long id : attractionIds) {
            Timed<float[]> cached = vectors.get(id);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                if (cached.value() != NO_VECTOR) {
                    result.put(id, cached.value());
                }
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            Map<Long, float[]> loaded = loader.apply(missing);
            Instant expiresAt = now.plus(vectorsTtl);
            for (Long id : missing) {
                float[] vector = loaded.get(id);
                vectors.put(id, new Timed<>(vector == null ? NO_VECTOR : vector, expiresAt));
                if (vector != null) {
                    result.put(id, vector);
                }
            }
        }
        return result;
    }

    /** 테스트가 매번 빈 상태로 시작하도록 비운다. */
    public void clear() {
        candidates.clear();
        vectors.clear();
    }
}
