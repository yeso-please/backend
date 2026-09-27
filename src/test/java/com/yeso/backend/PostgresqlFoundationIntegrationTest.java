package com.yeso.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "jwt.secret=test-only-secret-not-used-outside-automated-tests",
        "spring.jpa.properties.hibernate.default_schema=app"
})
class PostgresqlFoundationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("tripin_test")
            .withUsername("tripin_test")
            .withPassword("tripin_test");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

    @Test
    @DisplayName("빈 PostgreSQL에 V1 전체 스키마를 생성하고 JPA 검증을 통과한다")
    void migration_emptyDatabase_createsWholeSchema() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app.flyway_schema_history WHERE success = true AND version = '1'", Integer.class);
        Integer coreTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'app'
                  AND table_name IN (
                    'users', 'regions', 'region_contents', 'attractions', 'attraction_images',
                    'official_courses', 'official_course_stops', 'trip_plans', 'trip_participants',
                    'trip_stops', 'meal_stops', 'ingestion_runs', 'data_quality_issues'
                  )
                """, Integer.class);

        assertThat(successfulMigrations).isEqualTo(1);
        assertThat(coreTables).isEqualTo(13);
    }

    @Test
    @DisplayName("적용 완료된 migration을 다시 실행하면 변경 없이 종료한다")
    void migration_secondRun_isNoOp() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    @Test
    @Transactional
    @DisplayName("같은 원천 관광지 ID는 두 번 저장할 수 없다")
    void attraction_duplicateSource_isRejected() {
        jdbcTemplate.update("""
                INSERT INTO app.regions(sig_cd, province, city)
                VALUES ('11110', '서울특별시', '종로구')
                """);
        jdbcTemplate.update("""
                INSERT INTO app.attractions(name, category, region_id, source_system, source_content_id)
                VALUES ('테스트 관광지', '역사', '11110', 'TOUR_API', '100')
                """);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO app.attractions(name, category, region_id, source_system, source_content_id)
                VALUES ('중복 관광지', '역사', '11110', 'TOUR_API', '100')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("TourAPI 상세·음식점 확장 스키마와 원천 ID 유일성을 검증한다")
    void tourApiMigrationSchema_enforcesSourceAndDates() {
        Integer versions = jdbcTemplate.queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE success=true AND version IN ('2','3')", Integer.class);
        assertThat(versions).isEqualTo(2);
        Integer v4 = jdbcTemplate.queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE success=true AND version='4'", Integer.class);
        assertThat(v4).isEqualTo(1);
        jdbcTemplate.update("INSERT INTO app.regions(sig_cd,province,city) VALUES ('11110','서울특별시','종로구')");
        jdbcTemplate.update("INSERT INTO app.attractions(name,category,region_id,source_content_id,detail_fetched,event_start_date,event_end_date) VALUES ('축제','축제','11110','festival-1',true,'2026-09-01','2026-09-30')");
        jdbcTemplate.update("INSERT INTO app.restaurants(region_id,name,image_validation_status) VALUES ('11110','식당','PENDING')");
        Long restaurantId = jdbcTemplate.queryForObject("SELECT id FROM app.restaurants WHERE name='식당'", Long.class);
        jdbcTemplate.update("INSERT INTO app.restaurant_sources(restaurant_id,source_system,source_content_id) VALUES (?,'TOUR_API','food-1')", restaurantId);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO app.restaurant_sources(restaurant_id,source_system,source_content_id) VALUES (?,'TOUR_API','food-1')", restaurantId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
