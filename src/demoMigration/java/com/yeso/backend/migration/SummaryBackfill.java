package com.yeso.backend.migration;

import com.yeso.backend.attraction.application.ingestion.IngestionWriterLock;
import com.yeso.backend.attraction.domain.SummaryTags;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository;
import com.yeso.backend.attraction.infrastructure.AttractionQueryRepository.RepresentativeRow;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 지역·관광지 한 줄 소개와 태그 초안 생성·검수 도구(#89, docs/runbooks/summary-review.md).
 *
 * <pre>
 * --mode=dry-run  --scope=attractions|regions [--region=47130] [--max-calls=N]   대상 수·호출 수·planSha256 출력
 * --mode=apply    (dry-run과 같은 옵션) --confirm-plan=sha                        ai로 초안을 만들어 DRAFT로 저장
 * --mode=export   --scope=... --out=drafts.csv                                    DRAFT를 검수용 CSV로
 * --mode=import   --scope=... --in=reviewed.csv --reviewer=이름                   decision 열(APPROVE·REJECT)을 반영
 * </pre>
 *
 * 멱등: 근거(이름·분류·설명, 지역은 대표 관광지)와 프롬프트 버전의 해시가 같은 행은 다시 만들지 않는다.
 * 생성 문장·프롬프트·키는 콘솔에 찍지 않는다(검수 CSV에만 쓴다).
 */
public final class SummaryBackfill {

    static final String PROMPT_VERSION = "summary-v1";
    static final int ATTRACTIONS_PER_CALL = 10;
    static final int REPRESENTATIVES = 8;
    static final int ONE_LINE_MAX = 50;
    static final int TAGLINE_MAX = 30;
    private static final int DEFAULT_MAX_CALLS = 100;
    private static final int MAX_CALLS_PER_RUN = 1000;
    private static final int SOURCE_EXCERPT = 200;
    private static final String JOB_TYPE = "SUMMARY_DRAFTS";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> CSV_HEADER = List.of(
            "kind", "summary_id", "target_id", "target_name", "basis", "text", "tags", "source_excerpt",
            "decision", "edited_text", "edited_tags", "note", "source_hash");

    private SummaryBackfill() {
    }

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
            System.out.println("--mode=dry-run|apply|export|import --scope=attractions|regions [--region=sigCd] [--max-calls=1..1000] "
                    + "[--confirm-plan=sha] [--out=file] [--in=file --reviewer=name]");
            return;
        }
        String url = requiredEnv("DB_URL");
        guardTarget(url, options);
        try (Connection connection = DriverManager.getConnection(url, requiredEnv("DB_USERNAME"), requiredEnv("DB_PASSWORD"))) {
            connection.setSchema("app");
            switch (options.mode()) {
                case "dry-run" -> plan(connection, options).print(options);
                case "export" -> System.out.printf("DRAFT %d건 → %s%n", export(connection, options.scope(), Path.of(options.out())), options.out());
                case "apply" -> {
                    try (IngestionWriterLock ignored = IngestionWriterLock.acquire(connection, JOB_TYPE)) {
                        Plan plan = plan(connection, options);
                        plan.print(options);
                        if (!plan.hash().equals(options.confirmPlan())) {
                            throw new IllegalArgumentException("Apply rejected: dry-run planSha256와 같은 --confirm-plan 값이 필요합니다.");
                        }
                        Result result = apply(connection, plan, new HttpAiClient(requiredEnv("EMBEDDING_SERVICE_BASE_URL")));
                        System.out.println(result);
                    }
                }
                case "import" -> {
                    try (IngestionWriterLock ignored = IngestionWriterLock.acquire(connection, "SUMMARY_REVIEW")) {
                        Imported imported = importReview(connection, options.scope(), Path.of(options.in()), options.reviewer());
                        System.out.println(imported);
                    }
                }
                default -> throw new IllegalArgumentException("--mode must be dry-run, apply, export, or import");
            }
        }
    }

    // ---------- 대상 ----------

    record AttractionTarget(long id, String name, String lcls1, String lcls2, String lcls3, String regionName,
                            String description, String hash) {
        boolean hasDescription() {
            return description != null && description.strip().length() >= 20;
        }
    }

    record RegionTarget(String sigCd, String regionName, Map<String, Integer> classCounts,
                        List<RepresentativeRow> representatives, String hash) {
    }

    record Plan(String scope, List<AttractionTarget> attractions, List<RegionTarget> regions, int totalTargets,
                int calls, String hash) {
        void print(Options options) {
            long nameOnly = attractions.stream().filter(target -> !target.hasDescription()).count();
            System.out.printf("scope=%s region=%s targets=%d planned=%d (이름·분류만: %d) calls=%d maxCalls=%d promptVersion=%s planSha256=%s%n",
                    scope, options.region() == null ? "all" : options.region(), totalTargets,
                    scope.equals("attractions") ? attractions.size() : regions.size(), nameOnly, calls,
                    options.maxCalls(), PROMPT_VERSION, hash);
        }
    }

    static Plan plan(Connection connection, Options options) throws Exception {
        if (options.scope().equals("attractions")) {
            List<AttractionTarget> all = findAttractionTargets(connection, options.region());
            List<AttractionTarget> planned = all.stream().limit((long) options.maxCalls() * ATTRACTIONS_PER_CALL).toList();
            int calls = (planned.size() + ATTRACTIONS_PER_CALL - 1) / ATTRACTIONS_PER_CALL;
            return new Plan("attractions", planned, List.of(), all.size(), calls,
                    sha256(planned.stream().map(target -> target.id() + ":" + target.hash()).toList()));
        }
        List<RegionTarget> all = findRegionTargets(connection, options.region());
        List<RegionTarget> planned = all.stream().limit(options.maxCalls()).toList();
        return new Plan("regions", List.of(), planned, all.size(), planned.size(),
                sha256(planned.stream().map(target -> target.sigCd() + ":" + target.hash()).toList()));
    }

    /** 상세가 보이는 관광지(쇼핑·숙박·음식점·캠핑장 제외) 중 요약이 없거나 근거가 바뀐 곳. */
    static List<AttractionTarget> findAttractionTargets(Connection connection, String region) throws SQLException {
        List<AttractionTarget> targets = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, a.name, a.lcls_systm1, a.lcls_systm2, a.lcls_systm3, r.province || ' ' || r.city AS region_name,
                       a.description, s.source_hash
                FROM app.attractions a
                JOIN app.regions r ON r.sig_cd = a.region_id
                LEFT JOIN app.attraction_summaries s ON s.attraction_id = a.id
                WHERE coalesce(a.content_type_id, 12) NOT IN (32, 38, 39) AND coalesce(a.lcls_systm2, '') <> 'AC05'
                  AND (cast(? AS varchar) IS NULL OR a.region_id = ?)
                ORDER BY a.id
                """)) {
            statement.setString(1, region);
            statement.setString(2, region);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String description = rs.getString("description");
                    String hash = sha256(List.of(PROMPT_VERSION, rs.getString("name"), nullToEmpty(rs.getString("lcls_systm1")),
                            nullToEmpty(rs.getString("lcls_systm2")), nullToEmpty(rs.getString("lcls_systm3")),
                            rs.getString("region_name"), nullToEmpty(description).strip()));
                    if (hash.equals(rs.getString("source_hash"))) {
                        continue;
                    }
                    targets.add(new AttractionTarget(rs.getLong("id"), rs.getString("name"), rs.getString("lcls_systm1"),
                            rs.getString("lcls_systm2"), rs.getString("lcls_systm3"), rs.getString("region_name"),
                            description, hash));
                }
            }
        }
        return targets;
    }

    /** 대표 관광지가 하나 이상 있는 지역 중 요약이 없거나 근거가 바뀐 곳. */
    static List<RegionTarget> findRegionTargets(Connection connection, String region) throws SQLException {
        AttractionQueryRepository repository = repository(connection);
        Map<String, String> existing = new HashMap<>();
        List<String[]> regions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.sig_cd, r.province || ' ' || r.city AS region_name, s.source_hash
                FROM app.regions r LEFT JOIN app.region_summaries s ON s.region_id = r.sig_cd
                WHERE cast(? AS varchar) IS NULL OR r.sig_cd = ?
                ORDER BY r.sig_cd
                """)) {
            statement.setString(1, region);
            statement.setString(2, region);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    regions.add(new String[] {rs.getString(1), rs.getString(2)});
                    existing.put(rs.getString(1), rs.getString(3));
                }
            }
        }
        List<RegionTarget> targets = new ArrayList<>();
        for (String[] row : regions) {
            List<RepresentativeRow> representatives = repository.findRepresentativeAttractions(row[0], REPRESENTATIVES);
            if (representatives.isEmpty()) {
                continue;
            }
            Map<String, Integer> counts = repository.countClassCodes(row[0]);
            List<String> parts = new ArrayList<>(List.of(PROMPT_VERSION, row[1], counts.toString()));
            representatives.forEach(rep -> parts.add(rep.id() + "|" + rep.name() + "|" + nullToEmpty(rep.lclsSystm3())
                    + "|" + sha256(List.of(nullToEmpty(rep.description()).strip()))));
            String hash = sha256(parts);
            if (!hash.equals(existing.get(row[0]))) {
                targets.add(new RegionTarget(row[0], row[1], counts, representatives, hash));
            }
        }
        return targets;
    }

    // ---------- 생성 ----------

    /** ai 서버 계약(ai README "POST /summaries/*"). 테스트는 가짜를 끼운다. */
    interface AiClient {
        AttractionResponse summarizeAttractions(Map<String, Object> request) throws Exception;

        RegionResponse summarizeRegion(Map<String, Object> request) throws Exception;
    }

    record AttractionItem(String id, String oneLine, List<String> tags, String basis, String source, List<String> problems) {
    }

    record AttractionResponse(List<AttractionItem> items, String promptVersion, String generatorModel, String llmError) {
    }

    record RegionResponse(String tagline, List<String> tags, String source, List<String> problems, String promptVersion,
                          String generatorModel, String llmError) {
    }

    record Result(UUID runId, String status, int calls, int saved, int rejectedByVerifier) {
        @Override
        public String toString() {
            return "runId=%s status=%s calls=%d saved=%d verifierRejected=%d".formatted(runId, status, calls, saved, rejectedByVerifier);
        }
    }

    static Result apply(Connection connection, Plan plan, AiClient client) throws Exception {
        UUID runId = UUID.randomUUID();
        startRun(connection, runId, plan);
        int calls = 0;
        int saved = 0;
        int rejected = 0;
        String status = "SUCCEEDED";
        try {
            if (plan.scope().equals("attractions")) {
                for (int from = 0; from < plan.attractions().size(); from += ATTRACTIONS_PER_CALL) {
                    List<AttractionTarget> chunk = plan.attractions().subList(from, Math.min(from + ATTRACTIONS_PER_CALL, plan.attractions().size()));
                    calls++;
                    AttractionResponse response = client.summarizeAttractions(attractionRequest(runId, chunk));
                    String stop = stopReason(response.promptVersion(), response.llmError());
                    if (stop != null) {
                        status = stop;
                        break;
                    }
                    Map<String, AttractionTarget> byId = new HashMap<>();
                    chunk.forEach(target -> byId.put(String.valueOf(target.id()), target));
                    for (AttractionItem item : response.items() == null ? List.<AttractionItem>of() : response.items()) {
                        AttractionTarget target = byId.remove(item.id());
                        if (target == null) {
                            continue;
                        }
                        if (item.oneLine() == null || item.oneLine().isBlank() || item.oneLine().length() > ONE_LINE_MAX) {
                            rejected++;
                            continue;
                        }
                        saveAttraction(connection, target, item, response.generatorModel());
                        saved++;
                    }
                    rejected += byId.size();
                    progress(connection, runId, calls, "ATTRACTION:" + chunk.get(chunk.size() - 1).id());
                }
            } else {
                for (RegionTarget target : plan.regions()) {
                    calls++;
                    RegionResponse response = client.summarizeRegion(regionRequest(runId, target));
                    String stop = stopReason(response.promptVersion(), response.llmError());
                    if (stop != null) {
                        status = stop;
                        break;
                    }
                    if (response.tagline() == null || response.tagline().isBlank() || response.tagline().length() > TAGLINE_MAX) {
                        rejected++;
                    } else {
                        saveRegion(connection, target, response);
                        saved++;
                    }
                    progress(connection, runId, calls, "REGION:" + target.sigCd());
                }
            }
        } catch (Exception e) {
            finishRun(connection, runId, "FAILED", saved, rejected);
            throw e;
        }
        finishRun(connection, runId, status, saved, rejected);
        return new Result(runId, status, calls, saved, rejected);
    }

    /** 프롬프트 버전이 다르면 해시가 어긋나므로 멈춘다. LLM이 없거나 한도에 걸리면 규칙 태그만 저장하지 않고 멈춘다. */
    private static String stopReason(String promptVersion, String llmError) {
        if (!PROMPT_VERSION.equals(promptVersion)) {
            throw new IllegalStateException("ai 서버 promptVersion이 " + PROMPT_VERSION + "가 아닙니다: " + promptVersion);
        }
        if (llmError == null) {
            return null;
        }
        return llmError.equals("RATE_LIMITED") ? "QUOTA_EXHAUSTED" : "PARTIAL";
    }

    private static Map<String, Object> attractionRequest(UUID runId, List<AttractionTarget> chunk) {
        List<Map<String, Object>> items = chunk.stream().map(target -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(target.id()));
            item.put("name", target.name());
            item.put("lclsSystm1", target.lcls1());
            item.put("lclsSystm2", target.lcls2());
            item.put("lclsSystm3", target.lcls3());
            item.put("regionName", target.regionName());
            item.put("description", nullToEmpty(target.description()));
            return item;
        }).toList();
        return Map.of("requestId", runId.toString(), "items", items);
    }

    private static Map<String, Object> regionRequest(UUID runId, RegionTarget target) {
        List<Map<String, Object>> attractions = target.representatives().stream().map(rep -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(rep.id()));
            item.put("name", rep.name());
            item.put("lclsSystm1", rep.lclsSystm1());
            item.put("lclsSystm2", rep.lclsSystm2());
            item.put("lclsSystm3", rep.lclsSystm3());
            item.put("regionName", target.regionName());
            item.put("description", nullToEmpty(rep.description()));
            return item;
        }).toList();
        return Map.of("requestId", runId + ":" + target.sigCd(), "regionName", target.regionName(),
                "classCounts", target.classCounts(), "attractions", attractions);
    }

    /** 근거가 바뀌어 다시 만든 행은 승인 상태를 지우고 DRAFT로 돌린다. */
    private static void saveAttraction(Connection connection, AttractionTarget target, AttractionItem item, String model) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app.attraction_summaries (attraction_id, one_line, tags, basis, source_hash, generator_model, prompt_version)
                VALUES (?, ?, cast(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (attraction_id) DO UPDATE SET
                    one_line = excluded.one_line, tags = excluded.tags, basis = excluded.basis,
                    source_hash = excluded.source_hash, generator_model = excluded.generator_model,
                    prompt_version = excluded.prompt_version, status = 'DRAFT', reviewed_by = NULL, reviewed_at = NULL,
                    review_note = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE attraction_summaries.source_hash <> excluded.source_hash
                """)) {
            statement.setLong(1, target.id());
            statement.setString(2, item.oneLine().strip());
            statement.setString(3, JSON.writeValueAsString(SummaryTags.normalize(item.tags() == null ? List.of() : item.tags())));
            statement.setString(4, target.hasDescription() ? "SOURCE_SUMMARY" : "NAME_CATEGORY");
            statement.setString(5, target.hash());
            statement.setString(6, model);
            statement.setString(7, PROMPT_VERSION);
            statement.executeUpdate();
        }
    }

    private static void saveRegion(Connection connection, RegionTarget target, RegionResponse response) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app.region_summaries (region_id, tagline, tags, basis_attraction_ids, source_hash, generator_model, prompt_version)
                VALUES (?, ?, cast(? AS jsonb), cast(? AS jsonb), ?, ?, ?)
                ON CONFLICT (region_id) DO UPDATE SET
                    tagline = excluded.tagline, tags = excluded.tags, basis_attraction_ids = excluded.basis_attraction_ids,
                    source_hash = excluded.source_hash, generator_model = excluded.generator_model,
                    prompt_version = excluded.prompt_version, status = 'DRAFT', reviewed_by = NULL, reviewed_at = NULL,
                    review_note = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE region_summaries.source_hash <> excluded.source_hash
                """)) {
            statement.setString(1, target.sigCd());
            statement.setString(2, response.tagline().strip());
            statement.setString(3, JSON.writeValueAsString(SummaryTags.normalize(response.tags() == null ? List.of() : response.tags())));
            statement.setString(4, JSON.writeValueAsString(target.representatives().stream().map(RepresentativeRow::id).toList()));
            statement.setString(5, target.hash());
            statement.setString(6, response.generatorModel());
            statement.setString(7, PROMPT_VERSION);
            statement.executeUpdate();
        }
    }

    // ---------- 검수 ----------

    static int export(Connection connection, String scope, Path out) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        String sql = scope.equals("attractions") ? """
                SELECT 'ATTRACTION', s.id, a.id, a.name, s.basis, s.one_line, s.tags::text, a.description, s.source_hash
                FROM app.attraction_summaries s JOIN app.attractions a ON a.id = s.attraction_id
                WHERE s.status = 'DRAFT' ORDER BY s.basis, a.id
                """ : """
                SELECT 'REGION', s.id, r.sig_cd, r.province || ' ' || r.city, NULL, s.tagline, s.tags::text,
                       (SELECT string_agg(a.name, ', ' ORDER BY ord) FROM jsonb_array_elements_text(s.basis_attraction_ids)
                            WITH ORDINALITY AS ids(id, ord) JOIN app.attractions a ON a.id = cast(ids.id AS bigint)),
                       s.source_hash
                FROM app.region_summaries s JOIN app.regions r ON r.sig_cd = s.region_id
                WHERE s.status = 'DRAFT' ORDER BY r.sig_cd
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                List<String> tags = JSON.readValue(rs.getString(7), new TypeReference<List<String>>() { });
                String source = nullToEmpty(rs.getString(8)).replaceAll("\\s+", " ").strip();
                rows.add(List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), nullToEmpty(rs.getString(5)),
                        rs.getString(6), String.join(" ", tags), source.length() > SOURCE_EXCERPT ? source.substring(0, SOURCE_EXCERPT) + "…" : source,
                        "", "", "", "", rs.getString(9)));
            }
        }
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write('﻿');   // 엑셀이 한글을 깨뜨리지 않게
            writer.write(Csv.line(CSV_HEADER));
            for (List<String> row : rows) {
                writer.write(Csv.line(row));
            }
        }
        return rows.size();
    }

    record Imported(int approved, int rejected, int skippedStale, int unchanged) {
        @Override
        public String toString() {
            return "approved=%d rejected=%d staleOrNotDraft=%d noDecision=%d".formatted(approved, rejected, skippedStale, unchanged);
        }
    }

    private record Decision(int line, long summaryId, boolean approve, String text, List<String> tags, String note, String hash) {
    }

    /** 모든 줄을 먼저 검사하고, 하나라도 틀리면 아무것도 쓰지 않는다. DRAFT이고 해시가 같은 행만 바꾼다. */
    static Imported importReview(Connection connection, String scope, Path in, String reviewer) throws Exception {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("--reviewer가 필요합니다.");
        }
        String kind = scope.equals("attractions") ? "ATTRACTION" : "REGION";
        int maxLength = scope.equals("attractions") ? ONE_LINE_MAX : TAGLINE_MAX;
        List<List<String>> rows;
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8)) {
            rows = Csv.parse(reader);
        }
        if (rows.isEmpty() || !rows.get(0).equals(CSV_HEADER)) {
            throw new IllegalArgumentException("CSV 머리글이 export 형식과 다릅니다.");
        }
        List<Decision> decisions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int unchanged = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < CSV_HEADER.size(); c++) {
                row.put(CSV_HEADER.get(c), c < rows.get(i).size() ? rows.get(i).get(c).strip() : "");
            }
            String decision = row.get("decision").toUpperCase();
            if (decision.isEmpty()) {
                unchanged++;
                continue;
            }
            int line = i + 1;
            if (!row.get("kind").equals(kind)) {
                errors.add(line + "행: kind가 " + kind + "가 아닙니다");
                continue;
            }
            if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
                errors.add(line + "행: decision은 APPROVE 또는 REJECT입니다");
                continue;
            }
            String text = row.get("edited_text").isEmpty() ? row.get("text") : row.get("edited_text");
            if (text.isEmpty() || text.length() > maxLength) {
                errors.add(line + "행: 문장은 1~" + maxLength + "자입니다");
                continue;
            }
            List<String> tags;
            try {
                String rawTags = row.get("edited_tags").isEmpty() ? row.get("tags") : row.get("edited_tags");
                tags = SummaryTags.normalize(List.of(rawTags.split("[\\s,]+")));
            } catch (IllegalArgumentException e) {
                errors.add(line + "행: " + e.getMessage());
                continue;
            }
            try {
                decisions.add(new Decision(line, Long.parseLong(row.get("summary_id")), decision.equals("APPROVE"), text, tags,
                        row.get("note"), row.get("source_hash")));
            } catch (NumberFormatException e) {
                errors.add(line + "행: summary_id가 숫자가 아닙니다");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("검수 CSV에 오류가 있어 아무것도 반영하지 않았습니다:\n" + String.join("\n", errors));
        }
        String table = scope.equals("attractions") ? "attraction_summaries" : "region_summaries";
        String textColumn = scope.equals("attractions") ? "one_line" : "tagline";
        int approved = 0;
        int rejected = 0;
        int stale = 0;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE app.%s SET status = ?, %s = ?, tags = cast(? AS jsonb), reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                    review_note = nullif(?, ''), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND source_hash = ? AND status = 'DRAFT'
                """.formatted(table, textColumn))) {
            for (Decision decision : decisions) {
                statement.setString(1, decision.approve() ? "APPROVED" : "REJECTED");
                statement.setString(2, decision.text());
                statement.setString(3, JSON.writeValueAsString(decision.tags()));
                statement.setString(4, reviewer.strip());
                statement.setString(5, decision.note());
                statement.setLong(6, decision.summaryId());
                statement.setString(7, decision.hash());
                if (statement.executeUpdate() == 1) {
                    if (decision.approve()) {
                        approved++;
                    } else {
                        rejected++;
                    }
                } else {
                    stale++;
                }
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return new Imported(approved, rejected, stale, unchanged);
    }

    // ---------- 실행 기록 ----------

    private static void startRun(Connection connection, UUID runId, Plan plan) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app.ingestion_runs(id, job_type, source_system, status, requested_limit, source_checksum, summary)
                VALUES (?, ?, 'AI', 'RUNNING', ?, ?, jsonb_build_object('scope', ?, 'promptVersion', ?, 'actor', ?, 'host', ?))
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, JOB_TYPE);
            statement.setInt(3, plan.calls());
            statement.setString(4, plan.hash());
            statement.setString(5, plan.scope());
            statement.setString(6, PROMPT_VERSION);
            statement.setString(7, envOr("INGESTION_ACTOR", envOr("USERNAME", envOr("USER", "unknown"))));
            statement.setString(8, envOr("COMPUTERNAME", envOr("HOSTNAME", "unknown")));
            statement.executeUpdate();
        }
    }

    private static void progress(Connection connection, UUID runId, int calls, String cursor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE app.ingestion_runs SET call_count = ?, cursor_value = ? WHERE id = ?")) {
            statement.setInt(1, calls);
            statement.setString(2, cursor);
            statement.setObject(3, runId);
            statement.executeUpdate();
        }
    }

    private static void finishRun(Connection connection, UUID runId, String status, int saved, int skipped) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE app.ingestion_runs SET status = ?, updated_count = ?, skipped_count = ?, finished_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """)) {
            statement.setString(1, status);
            statement.setInt(2, saved);
            statement.setInt(3, skipped);
            statement.setObject(4, runId);
            statement.executeUpdate();
        }
    }

    // ---------- helpers ----------

    private static AttractionQueryRepository repository(Connection connection) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
        return new AttractionQueryRepository(jdbc, "app", "unused", 2, 384);
    }

    /** 원격 쓰기는 개발 RDS만, 스냅샷 이름을 남긴 경우에만 허용한다(TourApiDetailBackfill과 같은 규칙). */
    private static void guardTarget(String url, Options options) {
        String lower = url.toLowerCase();
        if (!url.startsWith("jdbc:postgresql://") || lower.contains("prod")) {
            throw new IllegalArgumentException("PostgreSQL non-production target required; production targets are refused.");
        }
        boolean writes = options.mode().equals("apply") || options.mode().equals("import");
        boolean local = lower.contains("localhost") || lower.contains("127.0.0.1");
        if (writes && !local) {
            if (!"dev".equals(System.getenv("MIGRATION_TARGET_ENV")) || !lower.matches(".*?/tripin_dev\\?.*sslmode=verify-full.*")) {
                throw new IllegalArgumentException("Remote write requires MIGRATION_TARGET_ENV=dev, database tripin_dev, and sslmode=verify-full.");
            }
            if (System.getenv("MIGRATION_BEFORE_SNAPSHOT") == null || System.getenv("MIGRATION_BEFORE_SNAPSHOT").isBlank()) {
                throw new IllegalArgumentException("Remote write requires the pre-write RDS snapshot name in MIGRATION_BEFORE_SNAPSHOT.");
            }
        }
    }

    static String sha256(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured in the process environment or local .env file.");
        }
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    record Options(String mode, String scope, String region, int maxCalls, String confirmPlan, String out, String in,
                   String reviewer, boolean help) {
        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            boolean help = false;
            for (String arg : args) {
                if (arg.equals("--help")) {
                    help = true;
                    continue;
                }
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Unknown argument. Use --help.");
                }
                String[] pair = arg.substring(2).split("=", 2);
                if (!List.of("mode", "scope", "region", "max-calls", "confirm-plan", "out", "in", "reviewer").contains(pair[0])) {
                    throw new IllegalArgumentException("Unknown option: " + pair[0]);
                }
                values.put(pair[0], pair[1]);
            }
            int maxCalls = Integer.parseInt(values.getOrDefault("max-calls", Integer.toString(DEFAULT_MAX_CALLS)));
            if (maxCalls < 1 || maxCalls > MAX_CALLS_PER_RUN) {
                throw new IllegalArgumentException("--max-calls must be between 1 and " + MAX_CALLS_PER_RUN);
            }
            String scope = values.getOrDefault("scope", "attractions");
            if (!List.of("attractions", "regions").contains(scope)) {
                throw new IllegalArgumentException("--scope must be attractions or regions");
            }
            String mode = values.getOrDefault("mode", "dry-run");
            if (mode.equals("export") && values.get("out") == null || mode.equals("import") && values.get("in") == null) {
                throw new IllegalArgumentException("export는 --out, import는 --in이 필요합니다.");
            }
            return new Options(mode, scope, values.get("region"), maxCalls, values.get("confirm-plan"), values.get("out"),
                    values.get("in"), values.get("reviewer"), help);
        }
    }

    /** RFC 4180 CSV. 따옴표·쉼표·줄바꿈이 든 칸은 따옴표로 감싼다. */
    static final class Csv {
        private Csv() {
        }

        static String line(List<String> cells) {
            List<String> quoted = new ArrayList<>();
            for (String cell : cells) {
                String value = cell == null ? "" : cell;
                quoted.add(value.matches("(?s).*[\",\\r\\n].*") ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
            }
            return String.join(",", quoted) + "\r\n";
        }

        static List<List<String>> parse(BufferedReader reader) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            boolean any = false;
            int c;
            boolean first = true;
            while ((c = reader.read()) != -1) {
                if (first) {
                    first = false;
                    if (c == '﻿') {
                        continue;
                    }
                }
                any = true;
                if (quoted) {
                    if (c == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            cell.append('"');
                        } else {
                            quoted = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        cell.append((char) c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n') {
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    any = false;
                } else if (c != '\r') {
                    cell.append((char) c);
                }
            }
            if (any) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }

    /** ai 서버 호출. 응답 원문은 오류 메시지에도 남기지 않는다. */
    static final class HttpAiClient implements AiClient {
        private final String baseUrl;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpAiClient(String baseUrl) {
            this.baseUrl = baseUrl.replaceAll("/+$", "");
        }

        @Override
        public AttractionResponse summarizeAttractions(Map<String, Object> request) throws Exception {
            return JSON.readValue(post("/summaries/attractions", request), AttractionResponse.class);
        }

        @Override
        public RegionResponse summarizeRegion(Map<String, Object> request) throws Exception {
            return JSON.readValue(post("/summaries/region", request), RegionResponse.class);
        }

        private String post(String path, Map<String, Object> body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ai 서버가 HTTP " + response.statusCode() + "로 응답했습니다(본문 생략).");
            }
            return response.body();
        }
    }
}
