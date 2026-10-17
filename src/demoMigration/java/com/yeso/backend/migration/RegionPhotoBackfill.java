package com.yeso.backend.migration;

import com.yeso.backend.attraction.application.ingestion.IngestionWriterLock;
import com.yeso.backend.migration.SummaryBackfill.Csv;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 지역 대표 사진 후보 적재·검수 도구(docs/runbooks/region-photo-review.md).
 *
 * <pre>
 * --mode=import-candidates --in=candidates.jsonl     관광사진 API 후보를 DRAFT로 저장(같은 사진은 다시 넣지 않는다)
 * --mode=export            --out=photos.csv          DRAFT를 검수용 CSV로
 * --mode=import            --in=reviewed.csv --reviewer=이름   decision 열(APPROVE·REJECT)을 반영. 지역마다 APPROVE는 한 장
 * </pre>
 *
 * 후보 JSONL 한 줄: {@code {"sigCd": "51150", "candidates": [{"galContentId", "title", "imageUrl", "photographer",
 * "location", "month"}]}}. 후보 수집은 관광사진 정보 API를 시군구 이름으로 검색해 촬영지가 그 지역인 사진만 고른 것이다.
 */
public final class RegionPhotoBackfill {

    static final String SOURCE_SYSTEM = "TOUR_PHOTO_GALLERY";
    static final String SOURCE_NAME = "한국관광공사";
    static final String SOURCE_URL = "https://www.data.go.kr/data/15101914/openapi.do";
    static final String LICENSE = "공공누리 제1유형";
    static final int MAX_CANDIDATES = 5;
    private static final Set<String> IMAGE_HOSTS = Set.of("tong.visitkorea.or.kr");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> CSV_HEADER = List.of(
            "sig_cd", "region_name", "source_content_id", "rank", "title", "location", "photographer", "month", "image_url",
            "decision", "note");

    private RegionPhotoBackfill() {
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
            System.out.println("--mode=import-candidates|export|import [--in=file] [--out=file] [--reviewer=name]");
            return;
        }
        String url = requiredEnv("DB_URL");
        guardTarget(url, options.mode());
        try (Connection connection = DriverManager.getConnection(url, requiredEnv("DB_USERNAME"), requiredEnv("DB_PASSWORD"))) {
            connection.setSchema("app");
            switch (options.mode()) {
                case "export" -> System.out.printf("DRAFT %d장 → %s%n", export(connection, Path.of(options.out())), options.out());
                case "import-candidates" -> {
                    try (IngestionWriterLock ignored = IngestionWriterLock.acquire(connection, "REGION_PHOTOS")) {
                        System.out.println(importCandidates(connection, Path.of(options.in())));
                    }
                }
                case "import" -> {
                    try (IngestionWriterLock ignored = IngestionWriterLock.acquire(connection, "REGION_PHOTO_REVIEW")) {
                        System.out.println(importReview(connection, Path.of(options.in()), options.reviewer()));
                    }
                }
                default -> throw new IllegalArgumentException("--mode must be import-candidates, export, or import");
            }
        }
    }

    // ---------- 후보 ----------

    record Loaded(int saved, int skippedRegion, int invalid) {
        @Override
        public String toString() {
            return "saved=%d unknownRegion=%d invalid=%d".formatted(saved, skippedRegion, invalid);
        }
    }

    /** 이미 검수한(DRAFT가 아닌) 사진은 건드리지 않는다. 이미지 주소는 관광공사 이미지 서버의 https만 받는다. */
    static Loaded importCandidates(Connection connection, Path in) throws Exception {
        Set<String> regions = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT sig_cd FROM app.regions");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                regions.add(rs.getString(1));
            }
        }
        int saved = 0;
        int unknown = 0;
        int invalid = 0;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app.region_hero_photos (region_id, source_system, source_content_id, image_url, title, photographer,
                    photography_location, photography_month, source_name, source_url, license_note, candidate_rank)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (region_id, source_system, source_content_id) DO UPDATE SET
                    image_url = excluded.image_url, title = excluded.title, photographer = excluded.photographer,
                    photography_location = excluded.photography_location, photography_month = excluded.photography_month,
                    candidate_rank = excluded.candidate_rank, updated_at = CURRENT_TIMESTAMP
                WHERE app.region_hero_photos.status = 'DRAFT'
                """)) {
            for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = JSON.readTree(line);
                String sigCd = row.path("sigCd").asString();
                if (!regions.contains(sigCd)) {
                    unknown++;
                    continue;
                }
                int rank = 0;
                for (JsonNode candidate : row.path("candidates")) {
                    if (rank >= MAX_CANDIDATES) {
                        break;
                    }
                    String id = candidate.path("galContentId").asString();
                    String image = candidate.path("imageUrl").asString();
                    if (id.isBlank() || !allowedImage(image)) {
                        invalid++;
                        continue;
                    }
                    rank++;
                    statement.setString(1, sigCd);
                    statement.setString(2, SOURCE_SYSTEM);
                    statement.setString(3, id);
                    statement.setString(4, image);
                    statement.setString(5, cut(candidate.path("title").asString(), 255));
                    statement.setString(6, cut(candidate.path("photographer").asString(), 255));
                    statement.setString(7, cut(candidate.path("location").asString(), 255));
                    String month = candidate.path("month").asString();
                    statement.setString(8, month.matches("\\d{6}") ? month : null);
                    statement.setString(9, SOURCE_NAME);
                    statement.setString(10, SOURCE_URL);
                    statement.setString(11, LICENSE);
                    statement.setInt(12, rank);
                    saved += statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return new Loaded(saved, unknown, invalid);
    }

    static boolean allowedImage(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && IMAGE_HOSTS.contains(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---------- 검수 ----------

    static int export(Connection connection, Path out) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.sig_cd, r.province || ' ' || r.city, p.source_content_id, p.candidate_rank, p.title, p.photography_location,
                       p.photographer, p.photography_month, p.image_url
                FROM app.region_hero_photos p JOIN app.regions r ON r.sig_cd = p.region_id
                WHERE p.status = 'DRAFT'
                ORDER BY r.sig_cd, p.candidate_rank, p.id
                """); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), text(rs.getString(5)),
                        text(rs.getString(6)), text(rs.getString(7)), text(rs.getString(8)), rs.getString(9), "", ""));
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

    record Imported(int approved, int rejected, int skippedNotDraft, int unchanged) {
        @Override
        public String toString() {
            return "approved=%d rejected=%d notDraft=%d noDecision=%d".formatted(approved, rejected, skippedNotDraft, unchanged);
        }
    }

    private record Decision(String sigCd, String sourceContentId, boolean approve, String note) {
    }

    /**
     * 모든 줄을 먼저 검사하고, 하나라도 틀리면 아무것도 쓰지 않는다. 지역마다 APPROVE는 한 장이고, 승인하면 그 지역에
     * 이미 승인된 사진은 REJECTED로 바꾼다. DRAFT인 행만 바꾼다.
     */
    static Imported importReview(Connection connection, Path in, String reviewer) throws Exception {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("--reviewer가 필요합니다.");
        }
        List<List<String>> rows;
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8)) {
            rows = Csv.parse(reader);
        }
        if (rows.isEmpty() || !rows.get(0).equals(CSV_HEADER)) {
            throw new IllegalArgumentException("CSV 머리글이 export 형식과 다릅니다.");
        }
        List<Decision> decisions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> approvedLine = new HashMap<>();
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
            if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
                errors.add(line + "행: decision은 APPROVE 또는 REJECT입니다");
                continue;
            }
            if (decision.equals("APPROVE")) {
                Integer previous = approvedLine.putIfAbsent(row.get("sig_cd"), line);
                if (previous != null) {
                    errors.add(line + "행: " + row.get("sig_cd") + " 지역은 " + previous + "행에서 이미 승인했습니다(지역마다 한 장)");
                    continue;
                }
            }
            if (row.get("sig_cd").isEmpty() || row.get("source_content_id").isEmpty()) {
                errors.add(line + "행: sig_cd와 source_content_id가 필요합니다");
                continue;
            }
            decisions.add(new Decision(row.get("sig_cd"), row.get("source_content_id"), decision.equals("APPROVE"), row.get("note")));
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("검수 CSV에 오류가 있어 아무것도 반영하지 않았습니다:\n" + String.join("\n", errors));
        }
        int approved = 0;
        int rejected = 0;
        int notDraft = 0;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement demote = connection.prepareStatement("""
                UPDATE app.region_hero_photos SET status = 'REJECTED', review_note = '다른 사진을 승인함', reviewed_by = ?,
                    reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE region_id = ? AND status = 'APPROVED' AND EXISTS (
                    SELECT 1 FROM app.region_hero_photos d
                    WHERE d.region_id = ? AND d.source_system = ? AND d.source_content_id = ? AND d.status = 'DRAFT')
                """);
             PreparedStatement update = connection.prepareStatement("""
                UPDATE app.region_hero_photos SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                    review_note = nullif(?, ''), updated_at = CURRENT_TIMESTAMP
                WHERE region_id = ? AND source_system = ? AND source_content_id = ? AND status = 'DRAFT'
                """)) {
            for (Decision decision : decisions) {
                if (decision.approve()) {
                    demote.setString(1, reviewer.strip());
                    demote.setString(2, decision.sigCd());
                    demote.setString(3, decision.sigCd());
                    demote.setString(4, SOURCE_SYSTEM);
                    demote.setString(5, decision.sourceContentId());
                    demote.executeUpdate();
                }
                update.setString(1, decision.approve() ? "APPROVED" : "REJECTED");
                update.setString(2, reviewer.strip());
                update.setString(3, decision.note());
                update.setString(4, decision.sigCd());
                update.setString(5, SOURCE_SYSTEM);
                update.setString(6, decision.sourceContentId());
                if (update.executeUpdate() == 1) {
                    if (decision.approve()) {
                        approved++;
                    } else {
                        rejected++;
                    }
                } else {
                    notDraft++;
                }
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return new Imported(approved, rejected, notDraft, unchanged);
    }

    // ---------- helpers ----------

    /** 원격 쓰기는 개발 RDS만, 스냅샷 이름을 남긴 경우에만 허용한다(SummaryBackfill과 같은 규칙). */
    private static void guardTarget(String url, String mode) {
        String lower = url.toLowerCase();
        if (!url.startsWith("jdbc:postgresql://") || lower.contains("prod")) {
            throw new IllegalArgumentException("PostgreSQL non-production target required; production targets are refused.");
        }
        boolean writes = !mode.equals("export");
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

    private static String cut(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured in the process environment or local .env file.");
        }
        return value;
    }

    record Options(String mode, String in, String out, String reviewer, boolean help) {
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
                if (!List.of("mode", "in", "out", "reviewer").contains(pair[0])) {
                    throw new IllegalArgumentException("Unknown option: " + pair[0]);
                }
                values.put(pair[0], pair[1]);
            }
            String mode = values.getOrDefault("mode", "export");
            if (mode.equals("export") && values.get("out") == null || !mode.equals("export") && values.get("in") == null) {
                throw new IllegalArgumentException("export는 --out, import-candidates·import는 --in이 필요합니다.");
            }
            return new Options(mode, values.get("in"), values.get("out"), values.get("reviewer"), help);
        }
    }
}
