package com.yeso.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Explicitly guarded Flyway commands for the development RDS only. */
public final class RdsMigration {

    private static final String DATABASE = "tripin_dev";
    private static final String MIGRATOR = "tripin_migrator";

    private RdsMigration() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            // Flyway/driver exception messages may contain connection details. Keep task output secret-free.
            System.err.println("Flyway 작업 실패: " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static void run(String[] args) {
        if (args.length != 1 || !List.of("info", "validate", "migrate").contains(args[0])) {
            throw new IllegalArgumentException("명령은 info, validate, migrate 중 하나여야 합니다.");
        }

        Target target = Target.fromEnvironment(System.getenv());
        Flyway flyway = configuration()
                .dataSource(target.url(), target.user(), target.password())
                .load();

        System.out.printf("대상: %s / %s (secret 미출력)%n", target.host(), DATABASE);
        printVersions(flyway.info());
        Instant started = Instant.now();
        switch (args[0]) {
            case "info" -> System.out.println("Flyway info 완료 (DB 변경 없음)");
            case "validate" -> {
                flyway.validate();
                System.out.println("Flyway validate 성공 (DB 변경 없음)");
            }
            case "migrate" -> {
                List<String> pendingBefore = pendingVersions(flyway.info());
                var result = flyway.migrate();
                System.out.printf("적용 version: %s%n", pendingBefore.isEmpty() ? "없음 (no-op)" : String.join(", ", pendingBefore));
                System.out.printf("실제 적용 수: %d%n", result.migrationsExecuted);
                printVersions(flyway.info());
            }
            default -> throw new IllegalStateException("지원하지 않는 명령");
        }
        System.out.printf("소요 시간: %d ms%n", Duration.between(started, Instant.now()).toMillis());
    }

    static FluentConfiguration configuration() {
        return Flyway.configure()
                .locations("classpath:db/migration")
                .schemas("app")
                .defaultSchema("app")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                // Pre-migration validation should allow pending migrations while still
                // rejecting checksum/name changes and applied migrations missing locally.
                .ignoreMigrationPatterns("*:pending");
    }

    private static void printVersions(MigrationInfoService info) {
        String current = info.current() == null ? "없음" : info.current().getVersion().getVersion();
        List<String> pending = pendingVersions(info);
        System.out.printf("현재 version: %s%n", current);
        System.out.printf("pending version: %s%n", pending.isEmpty() ? "없음" : String.join(", ", pending));
    }

    private static List<String> pendingVersions(MigrationInfoService info) {
        return Arrays.stream(info.pending())
                .map(MigrationInfo::getVersion)
                .map(version -> version == null ? "repeatable" : version.getVersion())
                .toList();
    }

    record Target(String url, String user, String password, String host) {
        static Target fromEnvironment(java.util.Map<String, String> env) {
            String targetEnv = env.get("MIGRATION_TARGET_ENV");
            String url = env.get("DB_URL");
            String user = env.get("FLYWAY_USER");
            String password = env.get("FLYWAY_PASSWORD");
            String caPath = env.get("RDS_CA_PATH");

            if (!"dev".equals(targetEnv)) {
                throw new IllegalArgumentException("MIGRATION_TARGET_ENV=dev만 허용됩니다.");
            }
            if (url == null || !url.startsWith("jdbc:postgresql://") || user == null || !MIGRATOR.equals(user)
                    || password == null || password.isBlank()) {
                throw new IllegalArgumentException("PostgreSQL dev RDS 접속 설정이 필요합니다.");
            }
            try {
                URI uri = URI.create(url.substring("jdbc:".length()));
                String host = uri.getHost();
                String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
                String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
                String urlCaPath = Arrays.stream(query.split("&"))
                        .map(parameter -> parameter.split("=", 2))
                        .filter(parameter -> parameter.length == 2
                                && "sslrootcert".equals(URLDecoder.decode(parameter[0], StandardCharsets.UTF_8)))
                        .map(parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8))
                        .findFirst()
                        .orElse("");
                String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
                if (host == null || !normalizedHost.endsWith(".rds.amazonaws.com")
                        || normalizedHost.contains("prod")
                        || !DATABASE.equals(database) || uri.getUserInfo() != null
                        || uri.getPort() != 5432
                        || Arrays.stream(query.split("&"))
                                .map(parameter -> parameter.toLowerCase(Locale.ROOT))
                                .noneMatch(parameter -> parameter.equals("sslmode=verify-full"))) {
                    throw new IllegalArgumentException("대상은 TLS 검증이 설정된 tripin_dev RDS여야 합니다.");
                }
                if (caPath == null || caPath.isBlank() || !Path.of(caPath).isAbsolute()
                        || !Files.isRegularFile(Path.of(caPath)) || urlCaPath.isBlank()
                        || !Path.of(urlCaPath).toAbsolutePath().normalize()
                                .equals(Path.of(caPath).toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("RDS_CA_PATH 인증서 파일을 찾을 수 없습니다.");
                }
                return new Target(url, user, password, host);
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalArgumentException("DB_URL 또는 인증서 경로를 확인할 수 없습니다.");
            }
        }
    }
}
