package com.yeso.backend.migration;

import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiDetailBackfillIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("dry-run 대상은 추천 관광지 유형과 소개가 비어 있고 아직 확인하지 않은 TourAPI 원천만 포함한다")
    void findTargets_excludesNonCandidatesAndPreviouslyCheckedSources() throws Exception {
        jdbcTemplate.update("INSERT INTO app.regions(sig_cd, province, city) VALUES ('01000', '서울특별시', '테스트구')");
        insertAttraction("tour-attraction-missing", 12, false);
        insertAttraction("tour-attraction-source-empty", 14, true);
        insertAttraction("tour-lodging", 32, false);
        insertAttraction("tour-attraction-described", 12, false, "이미 있는 설명");
        insertCourse("tour-course-missing");
        insertCourse("tour-course-source-empty");
        jdbcTemplate.update("""
                INSERT INTO app.data_quality_issues(entity_type, entity_key, issue_type, severity)
                VALUES ('OFFICIAL_COURSE', 'tour-course-source-empty', 'SOURCE_EMPTY_DESCRIPTION', 'INFO')
                """);

        List<TourApiDetailBackfill.Target> targets;
        try (Connection connection = dataSource()) {
            connection.setSchema("app");
            targets = TourApiDetailBackfill.findTargets(connection, "all", null);
        }

        assertThat(targets).extracting(TourApiDetailBackfill.Target::sourceContentId)
                .containsExactly("tour-attraction-missing", "tour-course-missing");
        assertThat(targets).extracting(TourApiDetailBackfill.Target::cursor)
                .allMatch(cursor -> cursor.startsWith("ATTRACTION:") || cursor.startsWith("COURSE:"));
        assertThat(TourApiDetailBackfill.fingerprint("all", 700, targets))
                .isEqualTo(TourApiDetailBackfill.fingerprint("all", 700, targets));
    }

    private void insertAttraction(String sourceId, int type, boolean fetched) {
        insertAttraction(sourceId, type, fetched, null);
    }

    private void insertAttraction(String sourceId, int type, boolean fetched, String description) {
        jdbcTemplate.update("""
                INSERT INTO app.attractions(name, category, region_id, description, source_system, source_content_id,
                                            content_type_id, detail_fetched)
                VALUES (?, 'TourAPI', '01000', ?, 'TOUR_API', ?, ?, ?)
                """, sourceId, description, sourceId, type, fetched);
    }

    private void insertCourse(String sourceId) {
        jdbcTemplate.update("""
                INSERT INTO app.official_courses(region_id, source_system, source_content_id, title)
                VALUES ('01000', 'TOUR_API', ?, ?)
                """, sourceId, sourceId);
    }

    private Connection dataSource() throws Exception {
        return dataSourceProvider.getConnection();
    }

    @Autowired
    private javax.sql.DataSource dataSourceProvider;
}
