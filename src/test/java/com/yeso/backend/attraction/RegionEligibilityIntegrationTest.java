package com.yeso.backend.attraction;

import com.yeso.backend.attraction.application.region.RegionEligibilityService;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.CourseCandidate;
import com.yeso.backend.attraction.application.region.RegionEligibilityService.RegionEligibility;
import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.attraction.domain.IneligibleReason;
import com.yeso.backend.attraction.domain.RegionNotFoundException;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 추첨 가능 지역·추천 가능 관광지 판정(docs/api/attraction.md 7장 머리). 데이터는 SQL로 준비한다. */
class RegionEligibilityIntegrationTest extends IntegrationTest {

    private static final String GYEONGJU = "47130";

    @Autowired
    private RegionEligibilityService service;

    @Autowired
    private JdbcTemplate jdbc;

    private void region(String sigCd) {
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values (?, '경상북도', ?, 35.85, 129.22)",
                sigCd, "시" + sigCd);
    }

    private void content(String sigCd, String status, String heroStatus, int promptVersion, String reviewedAt) {
        jdbc.update("""
                insert into app.region_contents (region_id, title, introduction, status, hero_image_validation_status,
                                                 prompt_version, reviewed_at)
                values (?, '소개', '본문', ?, ?, ?, cast(? as timestamp))
                """, sigCd, status, heroStatus, promptVersion, reviewedAt);
    }

    /** 조건을 다 갖춘 추천 가능 관광지. */
    private Long attraction(String sigCd) {
        return attraction(sigCd, 35.8, 129.2, "설명", 12, "VALID");
    }

    private Long attraction(String sigCd, Double lat, Double lng, String description, Integer contentType, String imageStatus) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr)
                values ('관광지', '관광지', ?, ?, ?, ?, ?, '경북 경주시') returning id
                """, Long.class, sigCd, description, lat, lng, contentType);
        if (imageStatus != null) {
            jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, ?)",
                    id, "https://img.example/" + id + ".jpg", imageStatus);
        }
        return id;
    }

    private void issue(Long attractionId, String severity, String status) {
        jdbc.update("""
                insert into app.data_quality_issues (entity_type, entity_key, issue_type, severity, status)
                values ('ATTRACTION', ?, 'MISSING_IMAGE', ?, ?)
                """, String.valueOf(attractionId), severity, status);
    }

    /** 승인 소개문·검증 대표 이미지 + 추천 가능 관광지 {@code count}곳. */
    private void readyRegion(String sigCd, int count) {
        region(sigCd);
        content(sigCd, "APPROVED", "VALID", 1, "2026-09-01 10:00:00");
        for (int i = 0; i < count; i++) {
            attraction(sigCd);
        }
    }

    private RegionEligibility evaluate(String sigCd, int days, String density) {
        return service.evaluateAll(days, density).stream().filter(r -> r.sigCd().equals(sigCd)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("추첨 가능 지역")
    class Eligibility {

        @Test
        @DisplayName("1일 RELAXED는 추천 가능 관광지 5곳부터 추첨 가능하다(1×4+1)")
        void requiredCount_boundary() {
            readyRegion(GYEONGJU, 5);
            readyRegion("11110", 4);

            assertThat(evaluate(GYEONGJU, 1, "RELAXED").drawEligible()).isTrue();
            RegionEligibility short1 = evaluate("11110", 1, "RELAXED");
            assertThat(short1.drawEligible()).isFalse();
            assertThat(short1.ineligibleReasons()).containsExactly(IneligibleReason.INSUFFICIENT_ATTRACTIONS);
        }

        @Test
        @DisplayName("같은 지역도 PACKED는 더 많은 관광지가 필요하다(1×6+1=7)")
        void packedNeedsMore() {
            readyRegion(GYEONGJU, 6);

            assertThat(evaluate(GYEONGJU, 1, "RELAXED").drawEligible()).isTrue();
            assertThat(evaluate(GYEONGJU, 1, "PACKED").drawEligible()).isFalse();
        }

        @Test
        @DisplayName("승인된 소개문이 없으면 소개문·대표 이미지 사유가 모두 붙는다")
        void noApprovedContent() {
            region(GYEONGJU);
            content(GYEONGJU, "DRAFT", "VALID", 1, null);
            for (int i = 0; i < 5; i++) {
                attraction(GYEONGJU);
            }

            assertThat(evaluate(GYEONGJU, 1, "RELAXED").ineligibleReasons())
                    .containsExactly(IneligibleReason.NO_APPROVED_CONTENT, IneligibleReason.NO_VALID_HERO_IMAGE);
        }

        @Test
        @DisplayName("가장 최근에 승인된 소개문의 대표 이미지로 판정한다")
        void latestApprovedContentWins() {
            readyRegion(GYEONGJU, 5); // 9/1 승인, 대표 이미지 VALID
            content(GYEONGJU, "APPROVED", "INVALID", 2, "2026-09-20 10:00:00");

            assertThat(evaluate(GYEONGJU, 1, "RELAXED").ineligibleReasons())
                    .containsExactly(IneligibleReason.NO_VALID_HERO_IMAGE);
        }

        @Test
        @DisplayName("지역 수·목록·단건 판정이 같은 결과를 낸다")
        void countListSingleAgree() {
            readyRegion(GYEONGJU, 5);
            readyRegion("11110", 2);

            assertThat(service.countEligible(1, "RELAXED")).isEqualTo(1);
            assertThat(service.findEligible(1, "RELAXED"))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.sigCd()).isEqualTo(GYEONGJU);
                        assertThat(r.lat()).isEqualTo(35.85);
                    });
            assertThat(service.isDrawEligible(GYEONGJU, 1, "RELAXED")).isTrue();
            assertThat(service.isDrawEligible("11110", 1, "RELAXED")).isFalse();
        }

        @Test
        @DisplayName("없는 지역은 REGION_NOT_FOUND, 일수가 1~7 밖이면 REGION_INVALID_DAYS다")
        void invalidInputs() {
            assertThatThrownBy(() -> service.isDrawEligible("99999", 1, "RELAXED"))
                    .isInstanceOf(RegionNotFoundException.class);
            assertThatThrownBy(() -> service.countEligible(0, "RELAXED"))
                    .hasMessageContaining("1~7");
            assertThatThrownBy(() -> service.countEligible(8, "RELAXED"))
                    .hasMessageContaining("1~7");
        }
    }

    @Nested
    @DisplayName("추천 가능 관광지")
    class Recommendable {

        @Test
        @DisplayName("좌표·설명·검증 이미지 중 하나라도 없거나 쇼핑·숙박·음식점이면 빠진다")
        void excludedByQuality() {
            region(GYEONGJU);
            Long ok = attraction(GYEONGJU);
            attraction(GYEONGJU, null, null, "설명", 12, "VALID");
            attraction(GYEONGJU, 35.8, 129.2, "   ", 12, "VALID");
            attraction(GYEONGJU, 35.8, 129.2, "설명", 12, "PENDING");
            attraction(GYEONGJU, 35.8, 129.2, "설명", 12, null);
            attraction(GYEONGJU, 35.8, 129.2, "설명", 39, "VALID");
            attraction(GYEONGJU, 35.8, 129.2, "설명", 32, "VALID");

            assertThat(service.findCourseCandidates(GYEONGJU)).extracting(CourseCandidate::attractionId).containsExactly(ok);
        }

        @Test
        @DisplayName("열린 ERROR 품질 이슈만 막고, WARNING이나 해결된 이슈는 막지 않는다")
        void blockingIssueIsOpenError() {
            region(GYEONGJU);
            Long blocked = attraction(GYEONGJU);
            Long warning = attraction(GYEONGJU);
            Long resolved = attraction(GYEONGJU);
            issue(blocked, "ERROR", "OPEN");
            issue(warning, "WARNING", "OPEN");
            issue(resolved, "ERROR", "RESOLVED");

            assertThat(service.findCourseCandidates(GYEONGJU)).extracting(CourseCandidate::attractionId)
                    .containsExactly(warning, resolved);
        }

        @Test
        @DisplayName("코스 후보는 유형·체류시간·첫 번째 검증 이미지를 담는다")
        void candidateFields() {
            region(GYEONGJU);
            Long museum = attraction(GYEONGJU, 35.8, 129.2, "박물관", 14, "VALID");
            jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status, display_order) values (?, 'https://img.example/first.jpg', 'VALID', 0)", museum);
            jdbc.update("update app.attraction_images set display_order = 5 where image_url = ?", "https://img.example/" + museum + ".jpg");
            attraction(GYEONGJU);

            List<CourseCandidate> candidates = service.findCourseCandidates(GYEONGJU);

            assertThat(candidates.get(0).category()).isEqualTo(AttractionCategory.HISTORY_CULTURE);
            assertThat(candidates.get(0).stayMinutes()).isEqualTo(120);
            assertThat(candidates.get(0).thumbnailUrl()).isEqualTo("https://img.example/first.jpg");
            assertThat(candidates.get(0).address()).isEqualTo("경북 경주시");
            assertThat(candidates.get(1).category()).isEqualTo(AttractionCategory.ETC);
            assertThat(candidates.get(1).stayMinutes()).isEqualTo(90);
        }

        @Test
        @DisplayName("없는 지역의 후보를 찾으면 REGION_NOT_FOUND다")
        void unknownRegion() {
            assertThatThrownBy(() -> service.findCourseCandidates("99999")).isInstanceOf(RegionNotFoundException.class);
        }
    }
}
