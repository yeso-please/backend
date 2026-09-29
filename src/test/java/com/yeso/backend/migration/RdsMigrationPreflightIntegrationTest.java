package com.yeso.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers
class RdsMigrationPreflightIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("tripin_test")
            .withUsername("tripin_test")
            .withPassword("tripin_test");

    @Test
    @DisplayName("RDS 사전 validate는 pending만 무시하고 기적용 migration 이력은 검증한다")
    void preflightValidation_allowsPendingMigration() {
        Flyway throughVersion14 = RdsMigration.configuration()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("14"))
                .load();
        throughVersion14.migrate();

        Flyway preflight = RdsMigration.configuration()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();

        assertThat(preflight.info().pending())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("15");
        assertThatCode(preflight::validate).doesNotThrowAnyException();
    }
}
