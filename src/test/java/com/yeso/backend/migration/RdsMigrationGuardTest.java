package com.yeso.backend.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdsMigrationGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsOnlyExplicitDevelopmentRdsWithMatchingCaBundle() throws Exception {
        Path ca = Files.createFile(tempDir.resolve("global-bundle.pem"));
        String encodedCaPath = URLEncoder.encode(ca.toString().replace('\\', '/'), StandardCharsets.UTF_8);
        Map<String, String> env = Map.of(
                "MIGRATION_TARGET_ENV", "dev",
                "DB_URL", "jdbc:postgresql://tripin-dev-postgres.abcdefghijkl.ap-northeast-2.rds.amazonaws.com:5432/tripin_dev?sslmode=verify-full&sslrootcert=" + encodedCaPath,
                "FLYWAY_USER", "tripin_migrator",
                "FLYWAY_PASSWORD", "test-secret",
                "RDS_CA_PATH", ca.toString());

        RdsMigration.Target target = RdsMigration.Target.fromEnvironment(env);

        org.assertj.core.api.Assertions.assertThat(target.host())
                .isEqualTo("tripin-dev-postgres.abcdefghijkl.ap-northeast-2.rds.amazonaws.com");
        org.assertj.core.api.Assertions.assertThat(target.password()).isEqualTo("test-secret");
    }

    @Test
    void rejectsMissingTargetEnvironment() {
        assertThatThrownBy(() -> RdsMigration.Target.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIGRATION_TARGET_ENV=dev");
    }

    @Test
    void rejectsProductionDatabaseEvenWhenTargetEnvironmentSaysDev() {
        Map<String, String> env = Map.of(
                "MIGRATION_TARGET_ENV", "dev",
                "DB_URL", "jdbc:postgresql://prod.abcdefghijkl.ap-northeast-2.rds.amazonaws.com:5432/tripin_prod?sslmode=verify-full&sslrootcert=C:/rds/global-bundle.pem",
                "FLYWAY_USER", "tripin_migrator",
                "FLYWAY_PASSWORD", "not-printed",
                "RDS_CA_PATH", "C:/rds/global-bundle.pem");

        assertThatThrownBy(() -> RdsMigration.Target.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tripin_dev RDS");
    }

    @Test
    void rejectsProductionEndpointEvenIfDatabaseIsNamedDev() {
        Map<String, String> env = Map.of(
                "MIGRATION_TARGET_ENV", "dev",
                "DB_URL", "jdbc:postgresql://tripin-production.abcdefghijkl.ap-northeast-2.rds.amazonaws.com:5432/tripin_dev?sslmode=verify-full&sslrootcert=C:/rds/global-bundle.pem",
                "FLYWAY_USER", "tripin_migrator",
                "FLYWAY_PASSWORD", "not-printed",
                "RDS_CA_PATH", "C:/rds/global-bundle.pem");

        assertThatThrownBy(() -> RdsMigration.Target.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tripin_dev RDS");
    }

    @Test
    void rejectsLocalDatabaseEvenWhenDatabaseNameMatches() {
        Map<String, String> env = Map.of(
                "MIGRATION_TARGET_ENV", "dev",
                "DB_URL", "jdbc:postgresql://localhost:5432/tripin_dev?sslmode=verify-full&sslrootcert=C:/rds/global-bundle.pem",
                "FLYWAY_USER", "tripin_migrator",
                "FLYWAY_PASSWORD", "not-printed",
                "RDS_CA_PATH", "C:/rds/global-bundle.pem");

        assertThatThrownBy(() -> RdsMigration.Target.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tripin_dev RDS");
    }

    @Test
    void rejectsWrongFlywayAccount() {
        Map<String, String> env = Map.of(
                "MIGRATION_TARGET_ENV", "dev",
                "DB_URL", "jdbc:postgresql://dev.abcdefghijkl.ap-northeast-2.rds.amazonaws.com:5432/tripin_dev?sslmode=verify-full&sslrootcert=C:/rds/global-bundle.pem",
                "FLYWAY_USER", "tripin_admin",
                "FLYWAY_PASSWORD", "not-printed",
                "RDS_CA_PATH", "C:/rds/global-bundle.pem");

        assertThatThrownBy(() -> RdsMigration.Target.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PostgreSQL dev RDS");
    }
}
