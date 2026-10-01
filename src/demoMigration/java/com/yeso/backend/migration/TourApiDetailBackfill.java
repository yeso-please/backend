package com.yeso.backend.migration;

import com.yeso.backend.attraction.application.ingestion.IngestionWriterLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Resumable, missing-description-only TourAPI backfill for RDS-backed tourism records. */
public final class TourApiDetailBackfill {

    private static final int DEFAULT_MAX_CALLS = 950;
    private static final int MAX_CALLS_PER_RUN = 950;
    private static final List<Integer> RECOMMENDABLE_CONTENT_TYPES = List.of(12, 14, 15, 28);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");

    private TourApiDetailBackfill() {}

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (IngestionWriterLock.WriterBusyException e) {
            System.err.println("실행 차단: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help()) {
            System.out.println("--mode=dry-run|apply|sync [--scope=attractions] [--max-calls=1..950] [--confirm-plan=<sha256>] [--resume-run-id=<uuid> --confirm-stopped-run-id=<uuid>]");
            return;
        }
        String url = requiredEnv("DB_URL");
        String user = requiredEnv("DB_USERNAME");
        String password = requiredEnv("DB_PASSWORD");
        String mode = options.mode();
        if (!List.of("dry-run", "apply", "sync").contains(mode)) throw new IllegalArgumentException("--mode must be dry-run, apply, or sync");
        if (mode.equals("sync") && options.resumeRunId() != null) throw new IllegalArgumentException("--mode=sync starts a new run; use --mode=apply to resume.");
        if (mode.equals("sync") && !options.scope().equals("attractions")) throw new IllegalArgumentException("--mode=sync only collects attraction descriptions; official courses are excluded from this plan.");
        guardTarget(url, mode, options);
        if (!mode.equals("dry-run")) requiredEnv("TOUR_API_KEY");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema("app");
            try (IngestionWriterLock ignored = IngestionWriterLock.acquire(connection, "TOURAPI_DETAIL_BACKFILL")) {
                ensureSchema(connection);
                System.out.printf("RDS 연결 확인: host=%s database=%s ssl=%s%n", databaseHost(url), databaseName(url), url.toLowerCase().contains("sslmode=verify-full") ? "verify-full" : "not-verified");
                if (options.resumeRunId() != null) {
                    resume(connection, options);
                    return;
                }
                List<Target> targets = findTargets(connection, options.scope(), null);
                List<Target> planned = targets.stream().limit(options.maxCalls()).toList();
                String planHash = fingerprint(options.scope(), options.maxCalls(), planned);
                long attractions = planned.stream().filter(t -> t.kind().equals("ATTRACTION")).count();
                long courses = planned.size() - attractions;
                System.out.printf("mode=%s database=%s scope=%s missingAttractions=%d missingCourses=%d planned=%d maxCalls=%d planSha256=%s%n",
                        mode,
                        databaseName(url), options.scope(),
                        targets.stream().filter(t -> t.kind().equals("ATTRACTION")).count(),
                        targets.stream().filter(t -> t.kind().equals("COURSE")).count(), planned.size(),
                        options.maxCalls(), planHash);
                if (mode.equals("dry-run")) return;
                if (mode.equals("apply") && !planHash.equals(options.confirmPlan())) {
                    throw new IllegalArgumentException("Apply rejected: dry-run planSha256와 동일한 --confirm-plan 값이 필요합니다.");
                }
                if (planned.isEmpty()) {
                    System.out.println("누락 설명이 없어 수집할 항목이 없습니다.");
                    return;
                }
                System.out.printf("적용 계획 확인: 관광지 %d건, 공식 코스 %d건, 최대 요청 %d회%n", attractions, courses, options.maxCalls());
                if (mode.equals("sync") && !confirmSync(databaseName(url), planned.size(), options.maxCalls())) {
                    System.out.println("사용자가 취소했습니다. TourAPI 호출과 RDS 적재는 수행하지 않았습니다.");
                    return;
                }
                assertNoOtherRunningRun(connection, null);
                execute(connection, options, planned, planHash);
            }
        }
    }

    private static void resume(Connection connection, Options options) throws Exception {
        UUID runId = UUID.fromString(options.resumeRunId());
        RunState state = readRun(connection, runId);
        if (state == null || !state.jobType().equals("TOURAPI_DETAIL_BACKFILL")) {
            throw new IllegalArgumentException("재개할 TourAPI backfill run을 찾지 못했습니다.");
        }
        if (state.status().equals("RUNNING")) {
            if (!runId.toString().equals(options.confirmStoppedRunId())) {
                throw new IllegalStateException("이 run은 RUNNING으로 남아 있습니다. 이전 프로세스가 종료된 것을 확인한 뒤 --confirm-stopped-run-id=" + runId + "를 추가하세요.");
            }
            updateRunStatus(connection, runId, "FAILED", "operator confirmed previous process stopped");
        } else if (!List.of("FAILED", "PARTIAL", "QUOTA_EXHAUSTED").contains(state.status())) {
            throw new IllegalArgumentException("현재 상태는 재개할 수 없습니다: " + state.status());
        }
        assertNoOtherRunningRun(connection, runId);
        updateRunForResume(connection, runId, actor(), host());
        List<Target> targets = findTargets(connection, state.scope(), state.cursor());
        List<Target> planned = targets.stream().limit(options.maxCalls()).toList();
        System.out.printf("mode=apply resumeRunId=%s scope=%s cursor=%s remaining=%d thisRunLimit=%d%n",
                runId, state.scope(), state.cursor(), targets.size(), options.maxCalls());
        if (planned.isEmpty()) {
            finishRun(connection, runId, "SUCCEEDED");
            System.out.println("남은 대상이 없습니다.");
            return;
        }
        execute(connection, options.withScope(state.scope()), planned, state.planHash(), runId);
    }

    private static void execute(Connection connection, Options options, List<Target> targets, String planHash) throws Exception {
        execute(connection, options, targets, planHash, UUID.randomUUID());
    }

    private static void execute(Connection connection, Options options, List<Target> targets, String planHash, UUID runId) throws Exception {
        if (options.resumeRunId() == null) startRun(connection, runId, options.scope(), planHash, options.maxCalls());
        int[] attempted = {0};
        TourApiClient client = new TourApiClient(requiredEnv("TOUR_API_KEY"),
                envOr("TOUR_API_BASE_URL", "https://apis.data.go.kr/B551011/KorService2"),
                () -> {
                    if (attempted[0] >= options.maxCalls()) throw new BudgetExhausted();
                    attempted[0]++;
                    incrementCallCount(connection, runId);
                });
        int updated = 0;
        int empty = 0;
        try {
            for (Target target : targets) {
                String overview = client.overview(target.sourceContentId(), target.contentTypeId());
                if (overview == null || overview.isBlank()) {
                    saveSourceEmpty(connection, target, runId);
                    empty++;
                } else {
                    saveDescription(connection, target, overview, runId);
                    updated++;
                }
                updateCursorAndCounts(connection, runId, target.cursor(), overview == null || overview.isBlank() ? 0 : 1,
                        overview == null || overview.isBlank() ? 1 : 0);
            }
            finishRun(connection, runId, "SUCCEEDED");
            System.out.printf("runId=%s status=SUCCEEDED updated=%d sourceEmpty=%d apiCalls=%d%n", runId, updated, empty, attempted[0]);
        } catch (BudgetExhausted e) {
            finishRun(connection, runId, "PARTIAL");
            System.out.printf("runId=%s status=PARTIAL apiCalls=%d. --resume-run-id=%s 로 이어서 실행할 수 있습니다.%n", runId, attempted[0], runId);
        } catch (TourApiQuotaException e) {
            finishRun(connection, runId, "QUOTA_EXHAUSTED");
            System.out.printf("runId=%s status=QUOTA_EXHAUSTED apiCalls=%d. 일일 한도 초기화 후 --resume-run-id=%s 로 이어서 실행하세요.%n", runId, attempted[0], runId);
        } catch (Exception e) {
            finishRun(connection, runId, "FAILED");
            throw e;
        }
    }

    static List<Target> findTargets(Connection connection, String scope, String cursor) throws SQLException {
        List<Target> targets = new ArrayList<>();
        if (!scope.equals("courses") && (cursor == null || cursor.startsWith("ATTRACTION:"))) {
            long afterId = cursor == null ? 0 : Long.parseLong(cursor.substring("ATTRACTION:".length()));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, source_content_id, content_type_id
                    FROM app.attractions
                    WHERE source_system='TOUR_API' AND source_content_id IS NOT NULL
                      AND content_type_id = ANY (?)
                      AND (description IS NULL OR btrim(description)='') AND NOT detail_fetched
                      AND id > ?
                    ORDER BY id
                    """)) {
                statement.setArray(1, connection.createArrayOf("integer", RECOMMENDABLE_CONTENT_TYPES.toArray()));
                statement.setLong(2, afterId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) targets.add(new Target("ATTRACTION", rs.getLong(1), rs.getString(2), rs.getInt(3)));
                }
            }
        }
        if (!scope.equals("attractions") && (cursor == null || cursor.startsWith("COURSE:"))) {
            long afterId = cursor == null ? 0 : Long.parseLong(cursor.substring("COURSE:".length()));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT c.id, c.source_content_id
                    FROM app.official_courses c
                    WHERE c.source_system='TOUR_API' AND c.source_content_id IS NOT NULL
                      AND (c.description IS NULL OR btrim(c.description)='') AND c.id > ?
                      AND NOT EXISTS (
                          SELECT 1 FROM app.data_quality_issues q
                          WHERE q.entity_type='OFFICIAL_COURSE' AND q.entity_key=c.source_content_id
                            AND q.issue_type='SOURCE_EMPTY_DESCRIPTION' AND q.status='OPEN'
                      )
                    ORDER BY c.id
                    """)) {
                statement.setLong(1, afterId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) targets.add(new Target("COURSE", rs.getLong(1), rs.getString(2), 25));
                }
            }
        }
        return targets;
    }

    private static void startRun(Connection connection, UUID runId, String scope, String planHash, int maxCalls) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app.ingestion_runs(id, job_type, source_system, status, requested_limit, source_checksum,
                                               application_version, summary)
                VALUES (?, 'TOURAPI_DETAIL_BACKFILL', 'TOUR_API', 'RUNNING', ?, ?, ?,
                        jsonb_build_object('scope', ?, 'planHash', ?, 'actor', ?, 'host', ?))
                """)) {
            statement.setObject(1, runId);
            statement.setInt(2, maxCalls);
            statement.setString(3, planHash);
            statement.setString(4, gitSha());
            statement.setString(5, scope);
            statement.setString(6, planHash);
            statement.setString(7, actor());
            statement.setString(8, host());
            statement.executeUpdate();
        }
    }

    private static void incrementCallCount(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE app.ingestion_runs SET call_count=call_count+1 WHERE id=? AND status='RUNNING'")) {
            statement.setObject(1, runId);
            if (statement.executeUpdate() != 1) throw new SQLException("ingestion run stopped before the API request");
        }
    }

    private static void saveDescription(Connection connection, Target target, String overview, UUID runId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (target.kind().equals("ATTRACTION")) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE app.attractions
                        SET description=?, detail_fetched=TRUE, detail_fetched_at=CURRENT_TIMESTAMP,
                            embedding_status='PENDING', updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND source_system='TOUR_API' AND source_content_id=?
                          AND (description IS NULL OR btrim(description)='')
                        """)) {
                    statement.setString(1, overview);
                    statement.setLong(2, target.id());
                    statement.setString(3, target.sourceContentId());
                    statement.executeUpdate();
                }
                resolveMissingDescription(connection, "ATTRACTION", target.sourceContentId());
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE app.official_courses
                        SET description=?, fetched_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND source_system='TOUR_API' AND source_content_id=?
                          AND (description IS NULL OR btrim(description)='')
                        """)) {
                    statement.setString(1, overview);
                    statement.setLong(2, target.id());
                    statement.setString(3, target.sourceContentId());
                    statement.executeUpdate();
                }
                resolveMissingDescription(connection, "OFFICIAL_COURSE", target.sourceContentId());
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void saveSourceEmpty(Connection connection, Target target, UUID runId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (target.kind().equals("ATTRACTION")) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE app.attractions SET detail_fetched=TRUE, detail_fetched_at=CURRENT_TIMESTAMP,
                                                   updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND source_system='TOUR_API' AND source_content_id=?
                        """)) {
                    statement.setLong(1, target.id());
                    statement.setString(2, target.sourceContentId());
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO app.data_quality_issues(ingestion_run_id, entity_type, entity_key, issue_type, severity, details)
                    VALUES (?, ?, ?, 'SOURCE_EMPTY_DESCRIPTION', 'INFO', jsonb_build_object('source', 'TourAPI detailCommon2'))
                    ON CONFLICT (entity_type, entity_key, issue_type, status) DO NOTHING
                    """)) {
                statement.setObject(1, runId);
                statement.setString(2, target.kind().equals("COURSE") ? "OFFICIAL_COURSE" : "ATTRACTION");
                statement.setString(3, target.sourceContentId());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void resolveMissingDescription(Connection connection, String entityType, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE app.data_quality_issues
                SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP
                WHERE entity_type=? AND entity_key=? AND issue_type='MISSING_DESCRIPTION' AND status='OPEN'
                """)) {
            statement.setString(1, entityType);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private static void updateCursorAndCounts(Connection connection, UUID runId, String cursor, int updated, int skipped) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE app.ingestion_runs
                SET cursor_value=?, updated_count=updated_count+?, skipped_count=skipped_count+?
                WHERE id=? AND status='RUNNING'
                """)) {
            statement.setString(1, cursor);
            statement.setInt(2, updated);
            statement.setInt(3, skipped);
            statement.setObject(4, runId);
            if (statement.executeUpdate() != 1) throw new SQLException("ingestion run stopped before cursor update");
        }
    }

    private static void finishRun(Connection connection, UUID runId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE app.ingestion_runs SET status=?, finished_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setString(1, status);
            statement.setObject(2, runId);
            statement.executeUpdate();
        }
    }

    private static RunState readRun(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT job_type, status, cursor_value, summary ->> 'scope', summary ->> 'planHash'
                FROM app.ingestion_runs WHERE id=?
                """)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? new RunState(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) : null;
            }
        }
    }

    private static void assertNoOtherRunningRun(Connection connection, UUID allowedRunId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, job_type, summary ->> 'actor' AS actor, summary ->> 'host' AS host, started_at, cursor_value
                FROM app.ingestion_runs WHERE status='RUNNING' ORDER BY started_at DESC
                """)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID runId = (UUID) rs.getObject("id");
                    if (runId.equals(allowedRunId)) continue;
                    throw new IngestionWriterLock.WriterBusyException("RDS에 RUNNING 적재 이력이 있어 새 작업을 차단했습니다. 작업="
                            + rs.getString("job_type") + ", 실행자=" + rs.getString("actor") + ", 호스트=" + rs.getString("host")
                            + ", runId=" + runId + ", 시작=" + rs.getObject("started_at")
                            + ", 진행위치=" + rs.getString("cursor_value")
                            + ". 이전 프로세스가 끝났는지 확인한 뒤 해당 run만 명시적으로 resume 하세요.");
                }
            }
        }
    }

    private static void updateRunStatus(Connection connection, UUID runId, String status, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE app.ingestion_runs SET status=?, finished_at=CURRENT_TIMESTAMP,
                    summary=summary || jsonb_build_object('interrupted', TRUE, 'interruptedReason', ?)
                WHERE id=?
                """)) {
            statement.setString(1, status);
            statement.setString(2, reason);
            statement.setObject(3, runId);
            statement.executeUpdate();
        }
    }

    private static void updateRunForResume(Connection connection, UUID runId, String actor, String host) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE app.ingestion_runs SET status='RUNNING', started_at=CURRENT_TIMESTAMP, finished_at=NULL,
                    summary=summary || jsonb_build_object('actor', ?, 'host', ?)
                WHERE id=?
                """)) {
            statement.setString(1, actor);
            statement.setString(2, host);
            statement.setObject(3, runId);
            statement.executeUpdate();
        }
    }

    private static void ensureSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT to_regclass('app.attractions'), to_regclass('app.official_courses'),
                       to_regclass('app.ingestion_runs'), to_regclass('app.data_quality_issues')
                """)) {
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || rs.getString(1) == null || rs.getString(2) == null || rs.getString(3) == null || rs.getString(4) == null) {
                    throw new SQLException("Required app schema is missing; apply the repository Flyway migrations first.");
                }
            }
        }
    }

    private static void guardTarget(String url, String mode, Options options) {
        String lower = url.toLowerCase();
        if (!url.startsWith("jdbc:postgresql://") || lower.contains("production") || lower.contains("prod")) {
            throw new IllegalArgumentException("PostgreSQL non-production target required; production targets are refused.");
        }
        boolean local = lower.contains("localhost") || lower.contains("127.0.0.1");
        if (!mode.equals("dry-run") && !local) {
            if (!"dev".equals(System.getenv("MIGRATION_TARGET_ENV")) || !lower.matches(".*?/tripin_dev\\?.*sslmode=verify-full.*")) {
                throw new IllegalArgumentException("Remote write requires MIGRATION_TARGET_ENV=dev, database tripin_dev, and sslmode=verify-full.");
            }
            if (System.getenv("MIGRATION_BEFORE_SNAPSHOT") == null || System.getenv("MIGRATION_BEFORE_SNAPSHOT").isBlank()) {
                throw new IllegalArgumentException("Remote write requires the pre-write RDS snapshot name in MIGRATION_BEFORE_SNAPSHOT.");
            }
        }
        if (mode.equals("apply") && options.resumeRunId() == null
                && (options.confirmPlan() == null || options.confirmPlan().isBlank())) {
            throw new IllegalArgumentException("Apply requires the exact --confirm-plan SHA-256 printed by dry-run.");
        }
    }

    static String fingerprint(String scope, int maxCalls, List<Target> targets) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((scope + "|" + maxCalls + "\n").getBytes(StandardCharsets.UTF_8));
        for (Target target : targets) digest.update((target.kind() + ":" + target.id() + ":" + target.sourceContentId() + ":" + target.contentTypeId() + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String databaseName(String url) {
        int query = url.indexOf('?');
        String path = url.substring(0, query < 0 ? url.length() : query);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String databaseHost(String url) {
        String authority = url.substring("jdbc:postgresql://".length()).split("/", 2)[0];
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.lastIndexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    private static boolean confirmSync(String database, int planned, int maxCalls) throws Exception {
        String expected = "APPLY " + database;
        System.out.printf("\nTourAPI 최대 %d회 호출, 설명 최대 %d건을 RDS '%s'에 적재합니다.%n", Math.min(planned, maxCalls), planned, database);
        System.out.printf("계속하려면 아래 문구를 입력하세요 (그 외 입력은 취소): %s%n> ", expected);
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return expected.equals(reader.readLine());
    }

    private static String actor() {
        return envOr("INGESTION_ACTOR", envOr("USERNAME", envOr("USER", "unknown")));
    }

    private static String host() {
        return envOr("COMPUTERNAME", envOr("HOSTNAME", "unknown"));
    }

    private static String gitSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return result.isBlank() ? "unknown" : result;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be configured in the process environment or local .env file.");
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String cleanOverview(String value) {
        String unescaped = value.replaceAll("(?i)<br\\s*/?>", "\n");
        return TAGS.matcher(unescaped).replaceAll(" ").replace("&nbsp;", " ").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&amp;", "&").replaceAll("[ \\t]+", " ").trim();
    }

    record Target(String kind, long id, String sourceContentId, int contentTypeId) {
        String cursor() { return kind + ":" + id; }
    }

    private record RunState(String jobType, String status, String cursor, String scope, String planHash) {}

    private record Options(String mode, String scope, int maxCalls, String confirmPlan,
                           String resumeRunId, String confirmStoppedRunId, boolean help) {
        static Options parse(String[] args) {
            Map<String, String> values = new java.util.HashMap<>();
            boolean help = false;
            for (String arg : args) {
                if (arg.equals("--help")) { help = true; continue; }
                if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("Unknown argument. Use --help.");
                String[] pair = arg.substring(2).split("=", 2);
                if (!List.of("mode", "scope", "max-calls", "confirm-plan", "resume-run-id", "confirm-stopped-run-id").contains(pair[0])) {
                    throw new IllegalArgumentException("Unknown option: " + pair[0]);
                }
                values.put(pair[0], pair[1]);
            }
            int maxCalls = Integer.parseInt(values.getOrDefault("max-calls", Integer.toString(DEFAULT_MAX_CALLS)));
            if (maxCalls < 1 || maxCalls > MAX_CALLS_PER_RUN) throw new IllegalArgumentException("--max-calls must be between 1 and " + MAX_CALLS_PER_RUN);
            String scope = values.getOrDefault("scope", "attractions");
            if (!List.of("all", "attractions", "courses").contains(scope)) throw new IllegalArgumentException("--scope must be all, attractions, or courses");
            return new Options(values.get("mode"), scope, maxCalls, values.get("confirm-plan"),
                    values.get("resume-run-id"), values.get("confirm-stopped-run-id"), help);
        }

        Options withScope(String value) { return new Options(mode, value, maxCalls, confirmPlan, resumeRunId, confirmStoppedRunId, help); }
    }

    @FunctionalInterface
    private interface BeforeRequest { void beforeRequest() throws SQLException; }

    private static final class BudgetExhausted extends RuntimeException {}
    private static final class TourApiQuotaException extends RuntimeException {}

    private static final class TourApiClient {
        private final String serviceKey;
        private final String baseUrl;
        private final BeforeRequest beforeRequest;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        private TourApiClient(String serviceKey, String baseUrl, BeforeRequest beforeRequest) {
            this.serviceKey = normalizeKey(serviceKey);
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.beforeRequest = beforeRequest;
        }

        private String overview(String contentId, int contentTypeId) throws Exception {
            String query = "serviceKey=" + encode(serviceKey)
                    + "&MobileOS=ETC&MobileApp=tripin-backfill&_type=json&contentId=" + encode(contentId);
            URI uri = URI.create(baseUrl + "/detailCommon2?" + query);
            for (int retry = 0; retry < 3; retry++) {
                beforeRequest.beforeRequest();
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    String body = response.body();
                    if (response.statusCode() == 429 || body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS") || body.contains("<returnReasonCode>22")) {
                        throw new TourApiQuotaException();
                    }
                    if (response.statusCode() >= 500 && retry < 2) { pause(retry); continue; }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("TourAPI returned HTTP " + response.statusCode() + "; response details omitted.");
                    }
                    JsonNode root;
                    try { root = JSON.readTree(body); }
                    catch (Exception e) { throw new IllegalStateException("TourAPI returned an unreadable response; response details omitted."); }
                    JsonNode responseNode = root.path("response");
                    // TourAPI deployments return either {response:{header,body}} or a flat {resultCode,resultMsg,body} object.
                    if (responseNode.isMissingNode() || responseNode.isNull()) responseNode = root;
                    JsonNode header = responseNode.path("header");
                    String code = header.path("resultCode").asString();
                    if (code.isBlank()) code = responseNode.path("resultCode").asString();
                    if ("22".equals(code) || responseNode.toString().contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) throw new TourApiQuotaException();
                    if (!"0000".equals(code)) {
                        String message = header.path("resultMsg").asString();
                        if (message.isBlank()) message = responseNode.path("resultMsg").asString();
                        throw new IllegalStateException("TourAPI rejected detailCommon2 request with code "
                                + (code.isBlank() ? "<missing>" : code) + " (" + (message.isBlank() ? "no message" : message)
                                + "); key and response omitted.");
                    }
                    JsonNode raw = responseNode.path("body").path("items").path("item");
                    JsonNode item = raw.isArray() ? (raw.isEmpty() ? null : raw.get(0)) : raw.isMissingNode() || raw.isNull() ? null : raw;
                    if (item == null) return null;
                    String returnedId = item.path("contentid").asString();
                    if (!contentId.equals(returnedId)) {
                        throw new IllegalStateException("TourAPI returned a different or missing contentid; expected ID was " + contentId + ".");
                    }
                    String overview = item.path("overview").asString();
                    String cleaned = cleanOverview(overview);
                    return cleaned.isBlank() ? null : cleaned;
                } catch (TourApiQuotaException | BudgetExhausted e) {
                    throw e;
                } catch (java.io.IOException e) {
                    if (retry == 2) throw new IllegalStateException("TourAPI network request failed after bounded retries; response details omitted.");
                    pause(retry);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("TourAPI request interrupted.");
                }
            }
            throw new IllegalStateException("TourAPI request failed after bounded retries.");
        }

        private static void pause(int retry) {
            try { Thread.sleep(500L * (retry + 1)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("TourAPI retry interrupted."); }
        }

        private static String normalizeKey(String key) {
            if (key.matches(".*%[0-9A-Fa-f]{2}.*")) return java.net.URLDecoder.decode(key, StandardCharsets.UTF_8);
            return key;
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
