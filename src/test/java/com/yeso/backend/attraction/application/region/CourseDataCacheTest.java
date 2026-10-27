package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.domain.AttractionCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** 미리 채운 후보·벡터는 요청 때 DB를 다시 읽지 않는다(CourseDataWarmer). */
class CourseDataCacheTest {

    private final CourseDataCache cache = new CourseDataCache(300, 600,
            Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC));

    private static CourseCandidate candidate(long id) {
        return new CourseCandidate(id, "관광지" + id, AttractionCategory.fromContentType(12), 90, "주소", 37.0, 127.0, null);
    }

    @Test
    @DisplayName("한꺼번에 넣은 지역 후보는 loader 없이 돌려준다")
    void putCandidates() {
        cache.putCandidates(Map.of("11110", List.of(candidate(1), candidate(2))));

        List<CourseCandidate> result = cache.candidates("11110", () -> fail("DB를 다시 읽으면 안 된다"));

        assertThat(result).extracting(CourseCandidate::attractionId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("한꺼번에 넣은 벡터는 loader 없이 돌려주고, 벡터가 없던 관광지도 다시 읽지 않는다")
    void putVectors() {
        float[] vector = {0.1f, 0.2f};
        cache.putVectors(List.of(1L, 2L), Map.of(1L, vector));

        Map<Long, float[]> result = cache.vectors(List.of(1L, 2L), missing -> fail("DB를 다시 읽으면 안 된다"));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L)).isSameAs(vector);
    }
}
