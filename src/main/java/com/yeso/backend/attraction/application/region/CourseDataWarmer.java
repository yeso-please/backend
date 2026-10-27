package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.infrastructure.RegionQualityRepository;
import com.yeso.backend.attraction.infrastructure.RegionQualityRepository.RegionRecommendableAttractionRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CourseDataCache}를 서버 시작 때 전국 단위로 한꺼번에 채우고, 기억 시간이 끝나기 전에 뒤에서 다시 채운다.
 *
 * <p>캐시만 있으면 지역마다 후보·벡터를 따로 읽어서, 취향 조건 지역 뽑기(3-7 {@code MY_TASTE})의 첫 요청과 기억 시간이
 * 끝난 뒤 첫 요청이 수백 번 DB를 왕복했다(개발 RDS 기준 30초 이상). 여기서는 후보를 쿼리 한 번, 벡터를 묶음 몇 번으로 읽는다.
 * 실패하면 경고만 남기고, 요청은 지금처럼 필요한 지역만 읽는다.
 * {@code course.cache.warm-enabled=false}면 bean을 만들지 않는다(테스트 profile은 끈다).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "course.cache", name = "warm-enabled", havingValue = "true", matchIfMissing = true)
public class CourseDataWarmer {

    private static final int VECTOR_CHUNK = 1000;

    private final RegionQualityRepository regionQualityRepository;
    private final CourseMaterialService courseMaterialService;
    private final CourseDataCache courseDataCache;
    private final Clock clock;
    /** 벡터는 후보보다 오래 기억하므로(기본 10분) 기억 시간의 절반이 지났을 때만 다시 읽는다. */
    private final Duration vectorRefreshAfter;
    private Instant vectorsLoadedAt = Instant.MIN;

    public CourseDataWarmer(
            RegionQualityRepository regionQualityRepository, CourseMaterialService courseMaterialService,
            CourseDataCache courseDataCache, Clock clock,
            @Value("${course.cache.vectors-ttl-seconds:600}") long vectorsTtlSeconds) {
        this.regionQualityRepository = regionQualityRepository;
        this.courseMaterialService = courseMaterialService;
        this.courseDataCache = courseDataCache;
        this.clock = clock;
        this.vectorRefreshAfter = Duration.ofSeconds(vectorsTtlSeconds / 2);
    }

    /** 후보 기억 시간(기본 5분)보다 짧은 간격으로 돈다. 처음 한 번은 서버가 뜨자마자. */
    @Scheduled(initialDelay = 0, fixedDelayString = "${course.cache.warm-interval-ms:240000}")
    public void warm() {
        try {
            long started = System.nanoTime();
            Map<String, List<CourseCandidate>> byRegion = new LinkedHashMap<>();
            for (RegionRecommendableAttractionRow row : regionQualityRepository.findAllRecommendableAttractions()) {
                byRegion.computeIfAbsent(row.getRegionId(), sigCd -> new ArrayList<>())
                        .add(RegionEligibilityService.toCandidate(row));
            }
            courseDataCache.putCandidates(byRegion);
            int vectorCount = -1;
            Instant now = clock.instant();
            if (!now.isBefore(vectorsLoadedAt.plus(vectorRefreshAfter))) {
                vectorCount = warmVectors(byRegion);
                vectorsLoadedAt = now;
            }
            log.info("course cache warmed: {} regions, {} candidates, {} vectors in {} ms", byRegion.size(),
                    byRegion.values().stream().mapToInt(List::size).sum(),
                    vectorCount < 0 ? "kept" : vectorCount, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("course cache warm-up failed (loaded on demand instead): {}", e.toString());
        }
    }

    private int warmVectors(Map<String, List<CourseCandidate>> byRegion) {
        List<Long> ids = byRegion.values().stream().flatMap(List::stream).map(CourseCandidate::attractionId).toList();
        int loaded = 0;
        for (int from = 0; from < ids.size(); from += VECTOR_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + VECTOR_CHUNK, ids.size()));
            Map<Long, float[]> vectors = new HashMap<>(courseMaterialService.loadVectors(chunk));
            courseDataCache.putVectors(chunk, vectors);
            loaded += vectors.size();
        }
        return loaded;
    }
}
