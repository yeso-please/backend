package com.yeso.backend.migration;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;

/** One-way, allowlisted TourAPI import. No demo entities or application credentials are loaded. */
public final class DemoMigration {
    private static final int BATCH = 200;
    private static final List<String> TABLES = List.of("REGION", "ATTRACTION", "FOOD_PLACE", "TRAVEL_COURSE", "COURSE_POINT");
    private static final Map<String, String> SELECTS = Map.of(
            "REGION", "SELECT SIG_CD, PROVINCE, NAME, LAT, LNG FROM REGION ORDER BY SIG_CD",
            "ATTRACTION", "SELECT ID, SOURCE_CONTENT_ID, SIG_CD, NAME, TYPE, DESCRIPTION, ADDR, LAT, LNG, IMAGE, HOMEPAGE, USETIME, RESTDATE, PARKING, INFOCENTER, TEL, DETAIL_FETCHED, EVENT_START_DATE, EVENT_END_DATE FROM ATTRACTION ORDER BY ID",
            "FOOD_PLACE", "SELECT ID, SOURCE_CONTENT_ID, SIG_CD, NAME, CATEGORY, DESCRIPTION, ADDR, LAT, LNG, IMAGE, USETIME, DETAIL_FETCHED FROM FOOD_PLACE ORDER BY ID",
            "TRAVEL_COURSE", "SELECT ID, SOURCE_CONTENT_ID, SIG_CD, TITLE, DESCRIPTION, THEME, TOTAL_DISTANCE FROM TRAVEL_COURSE ORDER BY ID",
            "COURSE_POINT", "SELECT COURSE_ID, POINT_INDEX, CONTENT_ID, NAME, TYPE, DESCRIPTION, IMAGE FROM COURSE_POINT ORDER BY COURSE_ID, POINT_INDEX");
    private static final Map<String, String> TARGETS = Map.of("REGION", "regions", "ATTRACTION", "attractions", "FOOD_PLACE", "restaurants", "TRAVEL_COURSE", "official_courses", "COURSE_POINT", "official_course_stops");

    private DemoMigration() {}

    public static void main(String[] args) throws Exception {
        run(args, System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/tripin_local?currentSchema=app"),
                System.getenv().getOrDefault("DB_USERNAME", "tripin_local"), System.getenv().getOrDefault("DB_PASSWORD", "tripin_local_dev_only"));
    }

    public static void run(String[] args, String url, String user, String password) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            System.out.println("--mode=dry-run|apply|validate --source=<absolute .mv.db> [--run-id=<uuid>] [--resume-run-id=<uuid>]");
            return;
        }
        String mode = options.get("mode");
        if (!List.of("dry-run", "apply", "validate").contains(mode)) throw new IllegalArgumentException("Invalid mode");
        guardTarget(url, mode, options);
        Path source = null;
        String sha = null;
        if (!mode.equals("validate")) {
            source = Path.of(required(options, "source"));
            if (!source.isAbsolute() || !source.toString().endsWith(".mv.db") || !Files.isRegularFile(source)) throw new IllegalArgumentException("Source must be an absolute .mv.db file");
            sha = sha256(source);
        }
        UUID runId = UUID.fromString(options.getOrDefault("run-id", options.getOrDefault("resume-run-id", UUID.randomUUID().toString())));
        Path report = Path.of("build", "reports", "demo-migration", runId.toString());
        Files.createDirectories(report);
        String jdbcUrl = url + (url.contains("?") ? "&" : "?") + "reWriteBatchedInserts=true";
        try (Connection pg = DriverManager.getConnection(jdbcUrl, user, password)) {
            pg.setSchema("app");
            checkFlyway(pg);
            if (mode.equals("validate")) {
                validate(pg, runId, report);
                return;
            }
            try (Connection h2 = DriverManager.getConnection("jdbc:h2:file:" + source.toString().substring(0, source.toString().length() - 6).replace('\\', '/') + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", "sa", "")) {
                h2.setReadOnly(true);
                Set<String> sourceRegions = new HashSet<>();
                try (Statement st = h2.createStatement(); ResultSet regions = st.executeQuery("SELECT SIG_CD FROM REGION")) {
                    while (regions.next()) sourceRegions.add(regions.getString(1));
                }
                String gitSha = gitSha();
                Counts counts = new Counts();
                write(report.resolve("manifest.json"), "{\"runId\":\"" + runId + "\",\"mode\":\"" + mode + "\",\"sourceSha256\":\"" + sha + "\",\"gitSha\":\"" + gitSha + "\",\"batchSize\":" + BATCH + "}");
                String resumeCursor = mode.equals("apply") ? startRun(pg, runId, sha, gitSha, options.containsKey("resume-run-id")) : null;
                try (BufferedWriter quarantine = Files.newBufferedWriter(report.resolve("quarantine.csv"))) {
                    quarantine.write("entity_type,source_key,error_code\n");
                    for (String table : TABLES) process(table, h2, pg, mode, counts, quarantine, runId, resumeCursor, sourceRegions);
                }
                if (mode.equals("apply")) { recordQuality(pg, runId); finishRun(pg, runId, counts); }
                writeReports(report, counts, pg, mode);
                System.out.println("runId=" + runId + " sourceSha256=" + sha + " report=" + report.toAbsolutePath());
                if (counts.quarantine > 0) throw new IllegalStateException("Quarantined rows: " + counts.quarantine + "; see report");
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.equals("--help")) { out.put("help", "true"); continue; }
            if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("Unknown argument: " + arg);
            String[] parts = arg.substring(2).split("=", 2);
            if (!List.of("mode", "source", "run-id", "resume-run-id").contains(parts[0])) throw new IllegalArgumentException("Unknown option: " + parts[0]);
            out.put(parts[0], parts[1]);
        }
        return out;
    }
    private static String required(Map<String, String> args, String key) { String value = args.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key); return value; }
    private static void guardTarget(String url, String mode, Map<String, String> options) {
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("PostgreSQL target required");
        String lower = url.toLowerCase();
        if (lower.contains("prod") || lower.contains("production")) throw new IllegalArgumentException("Production target refused");
        if (!lower.contains("localhost") && !lower.contains("127.0.0.1")) {
            if (!url.matches("jdbc:postgresql://[^/]+/tripin_dev\\?.*sslmode=verify-full.*") || !"dev".equals(System.getenv("MIGRATION_TARGET_ENV"))) {
                throw new IllegalArgumentException("Remote target requires tripin_dev and TLS verify-full");
            }
            if (mode.equals("apply") && (System.getenv("MIGRATION_BEFORE_SNAPSHOT") == null || !java.util.Objects.equals(System.getenv("MIGRATION_CONFIRM_RUN_ID"), options.get("run-id")) || options.get("run-id") == null)) {
                throw new IllegalArgumentException("Remote apply requires before snapshot and confirmed dry-run ID");
            }
        }
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) { byte[] chunk = new byte[65536]; int n; while ((n = in.read(chunk)) >= 0) digest.update(chunk, 0, n); }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    private static String gitSha() {
        try { Process p = new ProcessBuilder("git", "rev-parse", "HEAD").start(); String s = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor(); return s; }
        catch (Exception e) { return "unknown"; }
    }
    private static void checkFlyway(Connection pg) throws SQLException {
        try (Statement st = pg.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM app.flyway_schema_history WHERE success = TRUE AND version IN ('12','13','14')")) {
            if (!rs.next() || rs.getInt(1) != 3) throw new SQLException("Flyway V12-V14 required");
        }
    }
    private static String startRun(Connection pg, UUID runId, String sha, String git, boolean resume) throws SQLException {
        if (resume) {
            String cursor;
            try (PreparedStatement ps = pg.prepareStatement("SELECT source_checksum,application_version,status,cursor_value FROM app.ingestion_runs WHERE id=?")) {
                ps.setObject(1, runId); try (ResultSet rs = ps.executeQuery()) { if (!rs.next() || !sha.equals(rs.getString(1)) || !git.equals(rs.getString(2)) || !"FAILED".equals(rs.getString(3))) throw new SQLException("Resume checksum/version/status mismatch"); cursor = rs.getString(4); }
            }
            try (PreparedStatement ps = pg.prepareStatement("UPDATE app.ingestion_runs SET status='RUNNING' WHERE id=?")) { ps.setObject(1, runId); ps.executeUpdate(); }
            return cursor;
        } else {
            try (PreparedStatement ps = pg.prepareStatement("INSERT INTO app.ingestion_runs(id,job_type,source_system,status,source_checksum,application_version) VALUES (?,'DEMO_MIGRATION','TOUR_API','RUNNING',?,?)")) {
                ps.setObject(1, runId); ps.setString(2, sha); ps.setString(3, git); ps.executeUpdate();
            }
            return null;
        }
    }
    private static void finishRun(Connection pg, UUID runId, Counts counts) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement("UPDATE app.ingestion_runs SET status=?,inserted_count=?,updated_count=?,skipped_count=?,failed_count=?,finished_at=CURRENT_TIMESTAMP WHERE id=?")) {
            ps.setString(1, counts.quarantine == 0 ? "SUCCEEDED" : "PARTIAL"); ps.setInt(2, counts.inserted); ps.setInt(3, counts.updated); ps.setInt(4, counts.skipped); ps.setInt(5, counts.quarantine); ps.setObject(6, runId); ps.executeUpdate();
        }
    }
    private static void process(String table, Connection h2, Connection pg, String mode, Counts totals, BufferedWriter quarantine, UUID runId, String resumeCursor, Set<String> sourceRegions) throws Exception {
        if (mode.equals("apply") && (table.equals("ATTRACTION") || table.equals("FOOD_PLACE"))) {
            processFast(table, h2, pg, totals, quarantine, runId, resumeCursor, sourceRegions);
            return;
        }
        Counts c = totals.table(table);
        Set<String> seen = new HashSet<>();
        try (Statement st = h2.createStatement(); ResultSet rs = st.executeQuery(SELECTS.get(table))) {
            int batch = 0;
            if (mode.equals("apply")) pg.setAutoCommit(false);
            int resumeTable = resumeCursor == null ? -1 : TABLES.indexOf(resumeCursor.split(":", 2)[0]);
            int currentTable = TABLES.indexOf(table);
            int resumeRow = resumeCursor == null || resumeTable != currentTable ? 0 : Integer.parseInt(resumeCursor.split(":", 2)[1]);
            while (rs.next()) {
                c.source++;
                if (resumeTable > currentTable || (resumeTable == currentTable && c.source <= resumeRow)) { c.skipped++; continue; }
                String key = table.equals("REGION") ? rs.getString("SIG_CD") : table.equals("COURSE_POINT") ? rs.getLong("COURSE_ID") + ":" + rs.getInt("POINT_INDEX") : rs.getString("SOURCE_CONTENT_ID");
                try {
                    checkRow(table, rs, sourceRegions);
                    if (!seen.add(key)) throw new IllegalArgumentException("DUPLICATE_SOURCE_ID");
                    if (mode.equals("dry-run")) c.skipped++;
                    else {
                        int result = upsert(table, rs, h2, pg);
                        if (result == 1) c.inserted++; else if (result == 2) c.updated++; else c.skipped++;
                    }
                } catch (IllegalArgumentException ex) {
                    c.quarantine++;
                    quarantine.write(table + "," + csv(key) + "," + csv(ex.getMessage()) + "\n");
                }
                if (mode.equals("apply") && ++batch >= BATCH) { updateCursor(pg, runId, table, c.source); pg.commit(); batch = 0; }
            }
            if (mode.equals("apply")) { updateCursor(pg, runId, table, c.source); pg.commit(); pg.setAutoCommit(true); }
        } catch (Exception e) {
            if (mode.equals("apply")) { pg.rollback(); pg.setAutoCommit(true); try (PreparedStatement ps = pg.prepareStatement("UPDATE app.ingestion_runs SET status='FAILED',finished_at=CURRENT_TIMESTAMP WHERE id=?")) { ps.setObject(1, runId); ps.executeUpdate(); } }
            throw e;
        }
        totals.source += c.source; totals.inserted += c.inserted; totals.updated += c.updated; totals.skipped += c.skipped; totals.quarantine += c.quarantine;
        System.out.println(table + " source=" + c.source + " inserted=" + c.inserted + " updated=" + c.updated + " skipped=" + c.skipped + " quarantine=" + c.quarantine);
    }

    /** Pipeline inserts/updates in 200-row transactions instead of making several RDS round trips per row. */
    private static void processFast(String table, Connection h2, Connection pg, Counts totals, BufferedWriter quarantine,
                                    UUID runId, String resumeCursor, Set<String> sourceRegions) throws Exception {
        Counts c = totals.table(table);
        Set<String> seen = new HashSet<>();
        Map<String, Long> existing = new HashMap<>();
        String lookup = table.equals("ATTRACTION")
                ? "SELECT source_content_id,id FROM app.attractions WHERE source_system='TOUR_API'"
                : "SELECT source_content_id,restaurant_id FROM app.restaurant_sources WHERE source_system='TOUR_API'";
        try (Statement st = pg.createStatement(); ResultSet ids = st.executeQuery(lookup)) {
            while (ids.next()) existing.put(ids.getString(1), ids.getLong(2));
        }
        int resumeTable = resumeCursor == null ? -1 : TABLES.indexOf(resumeCursor.split(":", 2)[0]);
        int currentTable = TABLES.indexOf(table);
        int resumeRow = resumeCursor == null || resumeTable != currentTable ? 0 : Integer.parseInt(resumeCursor.split(":", 2)[1]);
        try (Statement st = h2.createStatement(); ResultSet rs = st.executeQuery(SELECTS.get(table));
             FastBatch batch = new FastBatch(table, pg, existing)) {
            pg.setAutoCommit(false);
            int rowInBatch = 0;
            while (rs.next()) {
                c.source++;
                if (resumeTable > currentTable || (resumeTable == currentTable && c.source <= resumeRow)) {
                    c.skipped++;
                    continue;
                }
                String key = rs.getString("SOURCE_CONTENT_ID");
                try {
                    checkRow(table, rs, sourceRegions);
                    if (!seen.add(key)) throw new IllegalArgumentException("DUPLICATE_SOURCE_ID");
                    batch.add(rs, key);
                } catch (IllegalArgumentException ex) {
                    c.quarantine++;
                    quarantine.write(table + "," + csv(key) + "," + csv(ex.getMessage()) + "\n");
                }
                if (++rowInBatch >= BATCH) {
                    batch.flush(c);
                    updateCursor(pg, runId, table, c.source);
                    pg.commit();
                    rowInBatch = 0;
                }
            }
            batch.flush(c);
            updateCursor(pg, runId, table, c.source);
            pg.commit();
            pg.setAutoCommit(true);
        } catch (Exception e) {
            pg.rollback();
            pg.setAutoCommit(true);
            try (PreparedStatement ps = pg.prepareStatement("UPDATE app.ingestion_runs SET status='FAILED',finished_at=CURRENT_TIMESTAMP WHERE id=?")) {
                ps.setObject(1, runId);
                ps.executeUpdate();
            }
            throw e;
        }
        totals.source += c.source; totals.inserted += c.inserted; totals.updated += c.updated;
        totals.skipped += c.skipped; totals.quarantine += c.quarantine;
        System.out.println(table + " source=" + c.source + " inserted=" + c.inserted + " updated=" + c.updated + " skipped=" + c.skipped + " quarantine=" + c.quarantine);
    }

    private record FastRow(String key, Object[] values, String image, long existingId) {}

    private static final class FastBatch implements AutoCloseable {
        private final String table;
        private final Connection pg;
        private final Map<String, Long> existing;
        private final List<FastRow> rows = new ArrayList<>();
        private final PreparedStatement insert;
        private final PreparedStatement update;
        private final PreparedStatement dependent;

        FastBatch(String table, Connection pg, Map<String, Long> existing) throws SQLException {
            this.table = table;
            this.pg = pg;
            this.existing = existing;
            if (table.equals("ATTRACTION")) {
                insert = pg.prepareStatement("INSERT INTO app.attractions(id,name,category,region_id,description,addr,lat,lng,source_system,source_content_id,content_type_id,homepage,use_time,rest_date,parking,info_center,tel,detail_fetched,event_start_date,event_end_date) VALUES (" + placeholders(20) + ")");
                update = pg.prepareStatement("UPDATE app.attractions SET name=?,category=?,region_id=?,description=COALESCE(?,description),addr=COALESCE(?,addr),lat=COALESCE(?,lat),lng=COALESCE(?,lng),content_type_id=?,homepage=COALESCE(?,homepage),use_time=COALESCE(?,use_time),rest_date=COALESCE(?,rest_date),parking=COALESCE(?,parking),info_center=COALESCE(?,info_center),tel=COALESCE(?,tel),detail_fetched=?,event_start_date=COALESCE(?,event_start_date),event_end_date=COALESCE(?,event_end_date),updated_at=CURRENT_TIMESTAMP WHERE id=? AND (name,category,region_id,description,addr,lat,lng,content_type_id,homepage,use_time,rest_date,parking,info_center,tel,detail_fetched,event_start_date,event_end_date) IS DISTINCT FROM (?,?,?,COALESCE(?,description),COALESCE(?,addr),COALESCE(?,lat),COALESCE(?,lng),?,COALESCE(?,homepage),COALESCE(?,use_time),COALESCE(?,rest_date),COALESCE(?,parking),COALESCE(?,info_center),COALESCE(?,tel),?,?,?)");
                dependent = pg.prepareStatement("INSERT INTO app.attraction_images(attraction_id,image_url,display_order,validation_status) VALUES (?,?,0,'PENDING') ON CONFLICT (attraction_id,image_url) DO NOTHING");
            } else {
                insert = pg.prepareStatement("INSERT INTO app.restaurants(id,region_id,name,category,addr,lat,lng,description,image_url,use_time,detail_fetched) VALUES (" + placeholders(11) + ")");
                update = pg.prepareStatement("UPDATE app.restaurants SET region_id=?,name=?,category=COALESCE(?,category),addr=COALESCE(?,addr),lat=COALESCE(?,lat),lng=COALESCE(?,lng),description=COALESCE(?,description),image_url=COALESCE(?,image_url),use_time=COALESCE(?,use_time),detail_fetched=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND (region_id,name,category,addr,lat,lng,description,image_url,use_time,detail_fetched) IS DISTINCT FROM (?,?,COALESCE(?,category),COALESCE(?,addr),COALESCE(?,lat),COALESCE(?,lng),COALESCE(?,description),COALESCE(?,image_url),COALESCE(?,use_time),?)");
                dependent = pg.prepareStatement("INSERT INTO app.restaurant_sources(restaurant_id,source_system,source_content_id) VALUES (?,'TOUR_API',?)");
            }
        }

        void add(ResultSet rs, String key) throws SQLException {
            long id = existing.getOrDefault(key, 0L);
            if (table.equals("ATTRACTION")) {
                int type = switch (rs.getString("TYPE")) {
                    case "관광지" -> 12; case "문화시설" -> 14; case "축제" -> 15;
                    case "레포츠" -> 28; case "숙박" -> 32; case "쇼핑" -> 38;
                    default -> throw new IllegalArgumentException("UNKNOWN_TYPE");
                };
                Object[] vals = {clean(rs.getString("NAME")), clean(rs.getString("TYPE")), rs.getString("SIG_CD"),
                        clean(rs.getString("DESCRIPTION")), clean(rs.getString("ADDR")), rs.getObject("LAT"), rs.getObject("LNG"),
                        key, type, clean(rs.getString("HOMEPAGE")), clean(rs.getString("USETIME")), clean(rs.getString("RESTDATE")),
                        clean(rs.getString("PARKING")), clean(rs.getString("INFOCENTER")), clean(rs.getString("TEL")),
                        rs.getBoolean("DETAIL_FETCHED"), date(rs.getString("EVENT_START_DATE")), date(rs.getString("EVENT_END_DATE"))};
                rows.add(new FastRow(key, vals, clean(rs.getString("IMAGE")), id));
            } else {
                Object[] vals = {rs.getString("SIG_CD"), clean(rs.getString("NAME")), clean(rs.getString("CATEGORY")),
                        clean(rs.getString("ADDR")), rs.getObject("LAT"), rs.getObject("LNG"), clean(rs.getString("DESCRIPTION")),
                        clean(rs.getString("IMAGE")), clean(rs.getString("USETIME")), rs.getBoolean("DETAIL_FETCHED")};
                rows.add(new FastRow(key, vals, null, id));
            }
        }

        void flush(Counts c) throws SQLException {
            if (rows.isEmpty()) return;
            int newCount = (int) rows.stream().filter(r -> r.existingId() == 0).count();
            long[] reserved = reserveIds(pg, table.equals("ATTRACTION") ? "attractions" : "restaurants", newCount);
            int nextId = 0;
            for (FastRow row : rows) {
                Object[] vals = row.values();
                long id = row.existingId();
                if (id == 0) {
                    id = reserved[nextId++];
                    if (table.equals("ATTRACTION")) {
                        bind(insert, concat(new Object[]{id}, concat(Arrays.copyOf(vals, 7), concat(new Object[]{"TOUR_API"}, Arrays.copyOfRange(vals, 7, vals.length)))));
                    } else {
                        bind(insert, concat(new Object[]{id}, vals));
                        bind(dependent, id, row.key());
                        dependent.addBatch();
                    }
                    insert.addBatch();
                    existing.put(row.key(), id);
                } else {
                    if (table.equals("ATTRACTION")) {
                        Object[] upd = {vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], vals[8], vals[9], vals[10], vals[11], vals[12], vals[13], vals[14], vals[15], vals[16], vals[17], id};
                        bind(update, concat(upd, Arrays.copyOf(upd, upd.length - 1)));
                    } else {
                        Object[] upd = concat(vals, new Object[]{id});
                        bind(update, concat(upd, vals));
                    }
                    update.addBatch();
                }
                if (table.equals("ATTRACTION") && row.image() != null) {
                    bind(dependent, id, row.image());
                    dependent.addBatch();
                }
            }
            if (newCount > 0) insert.executeBatch();
            int[] changed = update.executeBatch();
            dependent.executeBatch();
            c.inserted += newCount;
            for (int n : changed) { if (n > 0) c.updated++; else c.skipped++; }
            rows.clear();
        }

        @Override public void close() throws SQLException {
            insert.close(); update.close(); dependent.close();
        }
    }

    private static long[] reserveIds(Connection pg, String table, int count) throws SQLException {
        long[] ids = new long[count];
        if (count == 0) return ids;
        try (PreparedStatement ps = pg.prepareStatement("SELECT nextval(pg_get_serial_sequence('app." + table + "','id')) FROM generate_series(1,?)")) {
            ps.setInt(1, count);
            try (ResultSet rs = ps.executeQuery()) {
                for (int i = 0; i < count; i++) { if (!rs.next()) throw new SQLException("Not enough sequence IDs"); ids[i] = rs.getLong(1); }
            }
        }
        return ids;
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static void updateCursor(Connection pg, UUID runId, String table, int row) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement("UPDATE app.ingestion_runs SET cursor_value=? WHERE id=?")) { ps.setString(1, table + ":" + row); ps.setObject(2, runId); ps.executeUpdate(); }
    }
    private static void checkRow(String table, ResultSet rs, Set<String> sourceRegions) throws SQLException {
        if (table.equals("REGION")) { if (!validRegion(rs.getString("SIG_CD")) || blank(rs.getString("NAME")) || blank(rs.getString("PROVINCE"))) throw new IllegalArgumentException("INVALID_REGION"); return; }
        if (table.equals("COURSE_POINT")) { if (rs.getInt("POINT_INDEX") < 0 || blank(rs.getString("NAME"))) throw new IllegalArgumentException("INVALID_STOP"); return; }
        if (blank(rs.getString("SOURCE_CONTENT_ID"))) throw new IllegalArgumentException("MISSING_SOURCE_ID");
        if (!validRegion(rs.getString("SIG_CD"))) throw new IllegalArgumentException("INVALID_REGION");
        if (!sourceRegions.contains(rs.getString("SIG_CD"))) throw new IllegalArgumentException("UNKNOWN_REGION");
        if (blank(rs.getString(table.equals("TRAVEL_COURSE") ? "TITLE" : "NAME"))) throw new IllegalArgumentException("MISSING_NAME");
        if (table.equals("ATTRACTION") && !List.of("관광지", "문화시설", "레포츠", "숙박", "쇼핑", "축제").contains(rs.getString("TYPE"))) throw new IllegalArgumentException("UNKNOWN_TYPE");
        if (table.equals("ATTRACTION")) { date(rs.getString("EVENT_START_DATE")); date(rs.getString("EVENT_END_DATE")); }
    }
    private static boolean validRegion(String code) { return code != null && code.matches("[0-9]{5}"); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String clean(String s) { return blank(s) ? null : s.trim(); }
    private static LocalDate date(String s) { if (blank(s)) return null; try { return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE); } catch (DateTimeParseException e) { throw new IllegalArgumentException("INVALID_EVENT_DATE"); } }
    private static String csv(String s) { return s == null ? "" : s.replaceAll("[,\r\n]", "_"); }

    private static int upsert(String table, ResultSet rs, Connection h2, Connection pg) throws SQLException {
        return switch (table) {
            case "REGION" -> region(rs, pg);
            case "ATTRACTION" -> attraction(rs, pg);
            case "FOOD_PLACE" -> food(rs, pg);
            case "TRAVEL_COURSE" -> course(rs, pg);
            case "COURSE_POINT" -> stop(rs, h2, pg);
            default -> throw new IllegalArgumentException("UNKNOWN_TABLE");
        };
    }
    private static long id(Connection pg, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement(sql)) { bind(ps, args); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; } }
    }
    private static void bind(PreparedStatement ps, Object... args) throws SQLException { for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]); }
    private static int execute(Connection pg, String sql, Object... args) throws SQLException { try (PreparedStatement ps = pg.prepareStatement(sql)) { bind(ps, args); return ps.executeUpdate(); } }
    private static long regionId(Connection pg, String sig) throws SQLException { return id(pg, "SELECT 1 FROM app.regions WHERE sig_cd=?", sig); }
    private static void requireRegion(Connection pg, String sig) throws SQLException { if (regionId(pg, sig) == 0) throw new IllegalArgumentException("UNKNOWN_REGION"); }
    private static int region(ResultSet rs, Connection pg) throws SQLException {
        String key = rs.getString("SIG_CD");
        long exists = regionId(pg, key);
        if (exists == 0) return execute(pg, "INSERT INTO app.regions(sig_cd,province,city,lat,lng) VALUES (?,?,?,?,?)", key, clean(rs.getString("PROVINCE")), clean(rs.getString("NAME")), rs.getObject("LAT"), rs.getObject("LNG"));
        int n = execute(pg, "UPDATE app.regions SET province=?,city=?,lat=COALESCE(?,lat),lng=COALESCE(?,lng) WHERE sig_cd=? AND (province,city,lat,lng) IS DISTINCT FROM (?,?,COALESCE(?,lat),COALESCE(?,lng))", clean(rs.getString("PROVINCE")), clean(rs.getString("NAME")), rs.getObject("LAT"), rs.getObject("LNG"), key, clean(rs.getString("PROVINCE")), clean(rs.getString("NAME")), rs.getObject("LAT"), rs.getObject("LNG"));
        return n == 0 ? 0 : 2;
    }
    private static int attraction(ResultSet rs, Connection pg) throws SQLException {
        String key = clean(rs.getString("SOURCE_CONTENT_ID")); String sig = rs.getString("SIG_CD"); requireRegion(pg, sig);
        int type = switch (rs.getString("TYPE")) { case "관광지" -> 12; case "문화시설" -> 14; case "축제" -> 15; case "레포츠" -> 28; case "숙박" -> 32; case "쇼핑" -> 38; default -> throw new IllegalArgumentException("UNKNOWN_TYPE"); };
        long existing = id(pg, "SELECT id FROM app.attractions WHERE source_system='TOUR_API' AND source_content_id=?", key);
        Object[] vals = {clean(rs.getString("NAME")), clean(rs.getString("TYPE")), sig, clean(rs.getString("DESCRIPTION")), clean(rs.getString("ADDR")), rs.getObject("LAT"), rs.getObject("LNG"), key, type, clean(rs.getString("HOMEPAGE")), clean(rs.getString("USETIME")), clean(rs.getString("RESTDATE")), clean(rs.getString("PARKING")), clean(rs.getString("INFOCENTER")), clean(rs.getString("TEL")), rs.getBoolean("DETAIL_FETCHED"), date(rs.getString("EVENT_START_DATE")), date(rs.getString("EVENT_END_DATE"))};
        int changed;
        if (existing == 0) { changed = execute(pg, "INSERT INTO app.attractions(name,category,region_id,description,addr,lat,lng,source_system,source_content_id,content_type_id,homepage,use_time,rest_date,parking,info_center,tel,detail_fetched,event_start_date,event_end_date) VALUES (?,?,?,?,?,?,?,'TOUR_API',?,?,?,?,?,?,?,?,?,?,?)", vals); }
        else {
            Object[] upd = {vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], vals[8], vals[9], vals[10], vals[11], vals[12], vals[13], vals[14], vals[15], vals[16], vals[17], existing};
            changed = execute(pg, "UPDATE app.attractions SET name=?,category=?,region_id=?,description=COALESCE(?,description),addr=COALESCE(?,addr),lat=COALESCE(?,lat),lng=COALESCE(?,lng),content_type_id=?,homepage=COALESCE(?,homepage),use_time=COALESCE(?,use_time),rest_date=COALESCE(?,rest_date),parking=COALESCE(?,parking),info_center=COALESCE(?,info_center),tel=COALESCE(?,tel),detail_fetched=?,event_start_date=COALESCE(?,event_start_date),event_end_date=COALESCE(?,event_end_date),updated_at=CURRENT_TIMESTAMP WHERE id=? AND (name,category,region_id,description,addr,lat,lng,content_type_id,homepage,use_time,rest_date,parking,info_center,tel,detail_fetched,event_start_date,event_end_date) IS DISTINCT FROM (?,?,?,COALESCE(?,description),COALESCE(?,addr),COALESCE(?,lat),COALESCE(?,lng),?,COALESCE(?,homepage),COALESCE(?,use_time),COALESCE(?,rest_date),COALESCE(?,parking),COALESCE(?,info_center),COALESCE(?,tel),?,?,?)", concat(upd, Arrays.copyOf(upd, upd.length - 1)));
        }
        long targetId = existing == 0 ? id(pg, "SELECT id FROM app.attractions WHERE source_system='TOUR_API' AND source_content_id=?", key) : existing;
        String image = clean(rs.getString("IMAGE"));
        if (image != null) execute(pg, "INSERT INTO app.attraction_images(attraction_id,image_url,display_order,validation_status) VALUES (?,?,0,'PENDING') ON CONFLICT (attraction_id,image_url) DO NOTHING", targetId, image);
        return existing == 0 ? 1 : changed == 0 ? 0 : 2;
    }
    private static Object[] concat(Object[] a, Object[] b) { Object[] out = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, out, a.length, b.length); return out; }
    private static int food(ResultSet rs, Connection pg) throws SQLException {
        String key = clean(rs.getString("SOURCE_CONTENT_ID")); String sig = rs.getString("SIG_CD"); requireRegion(pg, sig);
        long existing = id(pg, "SELECT restaurant_id FROM app.restaurant_sources WHERE source_system='TOUR_API' AND source_content_id=?", key);
        Object[] vals = {sig, clean(rs.getString("NAME")), clean(rs.getString("CATEGORY")), clean(rs.getString("ADDR")), rs.getObject("LAT"), rs.getObject("LNG"), clean(rs.getString("DESCRIPTION")), clean(rs.getString("IMAGE")), clean(rs.getString("USETIME")), rs.getBoolean("DETAIL_FETCHED")};
        int changed;
        if (existing == 0) {
            changed = execute(pg, "INSERT INTO app.restaurants(region_id,name,category,addr,lat,lng,description,image_url,use_time,detail_fetched) VALUES (?,?,?,?,?,?,?,?,?,?)", vals);
            long restaurantId = id(pg, "SELECT id FROM app.restaurants WHERE region_id=? AND name=? ORDER BY id DESC LIMIT 1", sig, vals[1]);
            execute(pg, "INSERT INTO app.restaurant_sources(restaurant_id,source_system,source_content_id) VALUES (?,'TOUR_API',?)", restaurantId, key);
        } else {
            Object[] upd = concat(vals, new Object[]{existing});
            changed = execute(pg, "UPDATE app.restaurants SET region_id=?,name=?,category=COALESCE(?,category),addr=COALESCE(?,addr),lat=COALESCE(?,lat),lng=COALESCE(?,lng),description=COALESCE(?,description),image_url=COALESCE(?,image_url),use_time=COALESCE(?,use_time),detail_fetched=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND (region_id,name,category,addr,lat,lng,description,image_url,use_time,detail_fetched) IS DISTINCT FROM (?,?,COALESCE(?,category),COALESCE(?,addr),COALESCE(?,lat),COALESCE(?,lng),COALESCE(?,description),COALESCE(?,image_url),COALESCE(?,use_time),?)", concat(upd, vals));
        }
        return existing == 0 ? 1 : changed == 0 ? 0 : 2;
    }
    private static int course(ResultSet rs, Connection pg) throws SQLException {
        String key = clean(rs.getString("SOURCE_CONTENT_ID")); String sig = rs.getString("SIG_CD"); requireRegion(pg, sig);
        long existing = id(pg, "SELECT id FROM app.official_courses WHERE source_system='TOUR_API' AND source_content_id=?", key);
        Object[] vals = {sig, key, clean(rs.getString("TITLE")), clean(rs.getString("DESCRIPTION")), clean(rs.getString("THEME")), clean(rs.getString("TOTAL_DISTANCE"))};
        if (existing == 0) return execute(pg, "INSERT INTO app.official_courses(region_id,source_system,source_content_id,title,description,theme,total_distance_text) VALUES (?,'TOUR_API',?,?,?,?,?)", vals);
        Object[] upd = {vals[0], vals[2], vals[3], vals[4], vals[5], existing, vals[0], vals[2], vals[3], vals[4], vals[5]};
        int n = execute(pg, "UPDATE app.official_courses SET region_id=?,title=?,description=COALESCE(?,description),theme=COALESCE(?,theme),total_distance_text=COALESCE(?,total_distance_text),updated_at=CURRENT_TIMESTAMP WHERE id=? AND (region_id,title,description,theme,total_distance_text) IS DISTINCT FROM (?,?,COALESCE(?,description),COALESCE(?,theme),COALESCE(?,total_distance_text))", upd);
        return n == 0 ? 0 : 2;
    }
    private static int stop(ResultSet rs, Connection h2, Connection pg) throws SQLException {
        String parentSource;
        try (PreparedStatement ps = h2.prepareStatement("SELECT SOURCE_CONTENT_ID FROM TRAVEL_COURSE WHERE ID=?")) {
            ps.setLong(1, rs.getLong("COURSE_ID"));
            try (ResultSet parent = ps.executeQuery()) { if (!parent.next()) throw new IllegalArgumentException("UNKNOWN_COURSE"); parentSource = parent.getString(1); }
        }
        long courseId = id(pg, "SELECT id FROM app.official_courses WHERE source_system='TOUR_API' AND source_content_id=?", parentSource);
        if (courseId == 0) throw new IllegalArgumentException("UNKNOWN_COURSE");
        int order = rs.getInt("POINT_INDEX");
        String sourceId = clean(rs.getString("CONTENT_ID"));
        long attractionId = sourceId == null ? 0 : id(pg, "SELECT id FROM app.attractions WHERE source_system='TOUR_API' AND source_content_id=?", sourceId);
        Object attr = attractionId == 0 ? null : attractionId;
        long restaurantId = sourceId == null || attractionId != 0 ? 0 : id(pg, "SELECT restaurant_id FROM app.restaurant_sources WHERE source_system='TOUR_API' AND source_content_id=?", sourceId);
        Object restaurant = restaurantId == 0 ? null : restaurantId;
        long existing = id(pg, "SELECT id FROM app.official_course_stops WHERE official_course_id=? AND stop_order=?", courseId, order);
        Object[] vals = {courseId, order, attr, restaurant, sourceId, clean(rs.getString("NAME")), clean(rs.getString("TYPE")), clean(rs.getString("DESCRIPTION")), clean(rs.getString("IMAGE"))};
        if (existing == 0) return execute(pg, "INSERT INTO app.official_course_stops(official_course_id,stop_order,attraction_id,restaurant_id,source_content_id,name,category,description,image_url) VALUES (?,?,?,?,?,?,?,?,?)", vals);
        Object[] upd = {attr, restaurant, sourceId, vals[5], vals[6], vals[7], vals[8], existing, attr, restaurant, sourceId, vals[5], vals[6], vals[7], vals[8]};
        int n = execute(pg, "UPDATE app.official_course_stops SET attraction_id=COALESCE(?,attraction_id),restaurant_id=COALESCE(?,restaurant_id),source_content_id=COALESCE(?,source_content_id),name=?,category=COALESCE(?,category),description=COALESCE(?,description),image_url=COALESCE(?,image_url) WHERE id=? AND (attraction_id,restaurant_id,source_content_id,name,category,description,image_url) IS DISTINCT FROM (COALESCE(?,attraction_id),COALESCE(?,restaurant_id),COALESCE(?,source_content_id),?,COALESCE(?,category),COALESCE(?,description),COALESCE(?,image_url))", upd);
        return n == 0 ? 0 : 2;
    }
    private static void validate(Connection pg, UUID runId, Path report) throws Exception {
        try (PreparedStatement ps = pg.prepareStatement("SELECT status,source_checksum,inserted_count,updated_count,skipped_count,failed_count FROM app.ingestion_runs WHERE id=?")) {
            ps.setObject(1, runId); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Unknown run ID"); write(report.resolve("summary.md"), "# Validation\n\n- status: " + rs.getString(1) + "\n- source SHA-256: " + rs.getString(2) + "\n- inserted: " + rs.getInt(3) + "\n- updated: " + rs.getInt(4) + "\n- skipped: " + rs.getInt(5) + "\n- quarantine: " + rs.getInt(6) + "\n"); }
        }
        writeQuality(pg, report.resolve("quality.json"));
    }
    private static void recordQuality(Connection pg, UUID runId) throws SQLException {
        execute(pg, "UPDATE app.data_quality_issues q SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP FROM app.official_course_stops s JOIN app.official_courses c ON c.id=s.official_course_id WHERE q.entity_type='OFFICIAL_STOP' AND q.issue_type='OFFICIAL_STOP_UNMAPPED' AND q.status='OPEN' AND q.entity_key=c.source_content_id || ':' || s.stop_order AND (s.attraction_id IS NOT NULL OR s.restaurant_id IS NOT NULL)");
        String[][] jobs = {
                {"ATTRACTION", "MISSING_DESCRIPTION", "SELECT source_content_id FROM app.attractions WHERE source_system='TOUR_API' AND (description IS NULL OR btrim(description)='')"},
                {"ATTRACTION", "MISSING_COORDINATE", "SELECT source_content_id FROM app.attractions WHERE source_system='TOUR_API' AND (lat IS NULL OR lng IS NULL)"},
                {"ATTRACTION", "MISSING_IMAGE", "SELECT a.source_content_id FROM app.attractions a WHERE a.source_system='TOUR_API' AND NOT EXISTS (SELECT 1 FROM app.attraction_images i WHERE i.attraction_id=a.id)"},
                {"RESTAURANT", "MISSING_DESCRIPTION", "SELECT s.source_content_id FROM app.restaurant_sources s JOIN app.restaurants r ON r.id=s.restaurant_id WHERE s.source_system='TOUR_API' AND (r.description IS NULL OR btrim(r.description)='')"},
                {"RESTAURANT", "MISSING_IMAGE", "SELECT s.source_content_id FROM app.restaurant_sources s JOIN app.restaurants r ON r.id=s.restaurant_id WHERE s.source_system='TOUR_API' AND r.image_url IS NULL"},
                {"OFFICIAL_STOP", "OFFICIAL_STOP_UNMAPPED", "SELECT c.source_content_id || ':' || s.stop_order FROM app.official_course_stops s JOIN app.official_courses c ON c.id=s.official_course_id WHERE c.source_system='TOUR_API' AND s.attraction_id IS NULL AND s.restaurant_id IS NULL"}
        };
        for (String[] job : jobs) {
            String sql = "INSERT INTO app.data_quality_issues(ingestion_run_id,entity_type,entity_key,issue_type,severity) SELECT ?,?,?,?,?";
            // Fixed SQL fragments above select only allowlisted source IDs, never prose or user data.
            sql = "INSERT INTO app.data_quality_issues(ingestion_run_id,entity_type,entity_key,issue_type,severity) SELECT ?,?,q.source_content_id,?,'WARNING' FROM (" + job[2] + ") q(source_content_id) ON CONFLICT (entity_type,entity_key,issue_type,status) DO NOTHING";
            execute(pg, sql, runId, job[0], job[1]);
        }
    }
    private static void writeQuality(Connection pg, Path path) throws Exception {
        long regions = id(pg, "SELECT count(*) FROM app.regions");
        long attractions = id(pg, "SELECT count(*) FROM app.attractions WHERE source_system='TOUR_API'");
        long restaurants = id(pg, "SELECT count(*) FROM app.restaurant_sources WHERE source_system='TOUR_API'");
        long courses = id(pg, "SELECT count(*) FROM app.official_courses WHERE source_system='TOUR_API'");
        long stops = id(pg, "SELECT count(*) FROM app.official_course_stops s JOIN app.official_courses c ON c.id=s.official_course_id WHERE c.source_system='TOUR_API'");
        long withDescription = id(pg, "SELECT count(*) FROM app.attractions WHERE source_system='TOUR_API' AND description IS NOT NULL AND btrim(description)<>''");
        long withImage = id(pg, "SELECT count(DISTINCT a.id) FROM app.attractions a JOIN app.attraction_images i ON i.attraction_id=a.id WHERE a.source_system='TOUR_API'");
        long withCoordinates = id(pg, "SELECT count(*) FROM app.attractions WHERE source_system='TOUR_API' AND lat IS NOT NULL AND lng IS NOT NULL");
        long allThree = id(pg, "SELECT count(DISTINCT a.id) FROM app.attractions a JOIN app.attraction_images i ON i.attraction_id=a.id WHERE a.source_system='TOUR_API' AND a.description IS NOT NULL AND btrim(a.description)<>'' AND a.lat IS NOT NULL AND a.lng IS NOT NULL");
        long validImage = id(pg, "SELECT count(DISTINCT a.id) FROM app.attractions a JOIN app.attraction_images i ON i.attraction_id=a.id WHERE a.source_system='TOUR_API' AND i.validation_status='VALID'");
        long mappedAttractionStops = id(pg, "SELECT count(*) FROM app.official_course_stops s JOIN app.official_courses c ON c.id=s.official_course_id WHERE c.source_system='TOUR_API' AND s.attraction_id IS NOT NULL");
        long mappedRestaurantStops = id(pg, "SELECT count(*) FROM app.official_course_stops s JOIN app.official_courses c ON c.id=s.official_course_id WHERE c.source_system='TOUR_API' AND s.restaurant_id IS NOT NULL");
        write(path, "{\"regions\":" + regions + ",\"attractions\":" + attractions + ",\"restaurants\":" + restaurants + ",\"officialCourses\":" + courses + ",\"officialStops\":" + stops + ",\"attractionsWithDescription\":" + withDescription + ",\"attractionsWithImage\":" + withImage + ",\"attractionsWithCoordinates\":" + withCoordinates + ",\"attractionsWithAllThree\":" + allThree + ",\"attractionsWithValidatedImage\":" + validImage + ",\"mappedAttractionStops\":" + mappedAttractionStops + ",\"mappedRestaurantStops\":" + mappedRestaurantStops + "}");
    }
    private static void writeReports(Path path, Counts counts, Connection pg, String mode) throws Exception {
        StringBuilder json = new StringBuilder("{");
        for (var e : counts.tables.entrySet()) { if (json.length() > 1) json.append(','); Counts c = e.getValue(); json.append('"').append(e.getKey()).append("\":{\"source\":").append(c.source).append(",\"inserted\":").append(c.inserted).append(",\"updated\":").append(c.updated).append(",\"skipped\":").append(c.skipped).append(",\"quarantine\":").append(c.quarantine).append('}'); }
        json.append('}'); write(path.resolve("counts.json"), json.toString());
        writeQuality(pg, path.resolve("quality.json"));
        write(path.resolve("summary.md"), "# TourAPI migration " + mode + "\n\n- inserted: " + counts.inserted + "\n- updated: " + counts.updated + "\n- skipped: " + counts.skipped + "\n- quarantine: " + counts.quarantine + "\n- images remain PENDING; no region content is approved.\n");
    }
    private static void write(Path path, String content) throws Exception { Files.writeString(path, content); }
    private static final class Counts { int source, inserted, updated, skipped, quarantine; Map<String, Counts> tables = new LinkedHashMap<>(); Counts table(String name) { Counts c = new Counts(); tables.put(name, c); return c; } }
}
