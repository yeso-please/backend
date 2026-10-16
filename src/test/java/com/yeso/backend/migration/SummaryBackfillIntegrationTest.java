package com.yeso.backend.migration;

import com.yeso.backend.migration.SummaryBackfill.AttractionItem;
import com.yeso.backend.migration.SummaryBackfill.AttractionResponse;
import com.yeso.backend.migration.SummaryBackfill.Options;
import com.yeso.backend.migration.SummaryBackfill.Plan;
import com.yeso.backend.migration.SummaryBackfill.RegionResponse;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 한 줄 소개·태그 초안 배치와 검수 CSV(#89). ai 서버 대신 가짜 클라이언트를 쓴다. */
class SummaryBackfillIntegrationTest extends IntegrationTest {

    private static final String REGION = "51150";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @TempDir
    Path dir;

    private long described;
    private long nameOnly;

    /** 요청을 기록하고 정해진 답을 준다. 두 번째 장소의 문장은 검증에 걸린 것(null)으로 돌려준다. */
    private static final class FakeAi implements SummaryBackfill.AiClient {
        final List<Map<String, Object>> requests = new ArrayList<>();
        String llmError;

        @Override
        @SuppressWarnings("unchecked")
        public AttractionResponse summarizeAttractions(Map<String, Object> request) {
            requests.add(request);
            List<AttractionItem> items = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) request.get("items")) {
                boolean fail = ((String) item.get("name")).contains("실패");
                items.add(new AttractionItem((String) item.get("id"), fail ? null : item.get("name") + " 바람 따라 걷는 곳",
                        List.of("바다", "감성여행"), "SOURCE_SUMMARY", fail ? "rule" : "llm", List.of()));
            }
            return new AttractionResponse(items, "summary-v1", "fake-llm", llmError);
        }

        @Override
        public RegionResponse summarizeRegion(Map<String, Object> request) {
            requests.add(request);
            return new RegionResponse("파도 소리에 느긋해지는 바닷가", List.of("바다", "힐링"), "llm", List.of(), "summary-v1",
                    "fake-llm", llmError);
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.update("insert into app.regions (sig_cd, province, city) values (?, '강원특별자치도', '강릉시')", REGION);
        described = attraction("경포해변", "고운 모래와 소나무 숲이 이어지는 해변으로 여름이면 해수욕객이 찾는다.", 12);
        nameOnly = attraction("솔바람다리", null, 12);
        attraction("실패하는 곳", "설명이 충분히 긴 관광지 원천 설명입니다. 스무 글자를 넘깁니다.", 12);
        attraction("쇼핑몰", "설명", 38);
        jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, 'https://img/1.jpg', 'VALID')", described);
        jdbc.update("update app.attractions set lat = 37.8, lng = 128.9, lcls_systm1 = 'NA', lcls_systm2 = 'NA02', lcls_systm3 = 'NA020900' where id = ?", described);
    }

    private long attraction(String name, String description, int type) {
        return jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, content_type_id, source_system, source_content_id)
                values (?, '관광지', ?, ?, ?, 'TOUR_API', ?) returning id
                """, Long.class, name, REGION, description, type, "s-" + name);
    }

    private static Options options(String scope, int maxCalls) {
        return Options.parse(new String[] {"--mode=dry-run", "--scope=" + scope, "--max-calls=" + maxCalls});
    }

    @Test
    @DisplayName("초안을 DRAFT로 저장하고, 같은 입력으로 다시 실행하면 대상이 없다(멱등). 근거가 바뀌면 다시 만들고 승인을 지운다")
    void apply_isIdempotentAndResetsApprovalWhenSourceChanges() throws Exception {
        FakeAi ai = new FakeAi();
        try (Connection connection = dataSource.getConnection()) {
            Plan plan = SummaryBackfill.plan(connection, options("attractions", 5));
            assertThat(plan.totalTargets()).isEqualTo(3);   // 쇼핑은 대상이 아니다
            assertThat(plan.calls()).isEqualTo(1);
            SummaryBackfill.Result result = SummaryBackfill.apply(connection, plan, ai);
            assertThat(result.saved()).isEqualTo(2);
            assertThat(result.rejectedByVerifier()).isEqualTo(1);

            assertThat(jdbc.queryForObject("select basis from app.attraction_summaries where attraction_id = ?", String.class, nameOnly))
                    .isEqualTo("NAME_CATEGORY");
            assertThat(jdbc.queryForObject("select basis || status from app.attraction_summaries where attraction_id = ?", String.class, described))
                    .isEqualTo("SOURCE_SUMMARYDRAFT");
            assertThat(jdbc.queryForObject("select status from app.ingestion_runs where job_type = 'SUMMARY_DRAFTS'", String.class))
                    .isEqualTo("SUCCEEDED");

            // 검증에 걸린 곳만 남는다. 성공한 곳은 다시 부르지 않는다.
            Plan again = SummaryBackfill.plan(connection, options("attractions", 5));
            assertThat(again.attractions()).extracting(SummaryBackfill.AttractionTarget::name).containsExactly("실패하는 곳");

            jdbc.update("update app.attraction_summaries set status = 'APPROVED', reviewed_by = 'x', reviewed_at = now() where attraction_id = ?", described);
            jdbc.update("update app.attractions set description = description || ' 바뀐 설명' where id = ?", described);
            SummaryBackfill.apply(connection, SummaryBackfill.plan(connection, options("attractions", 5)), ai);
            assertThat(jdbc.queryForObject("select status from app.attraction_summaries where attraction_id = ?", String.class, described))
                    .isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("select count(*) from app.attraction_summaries", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("LLM을 못 쓰면 규칙 태그만으로 저장하지 않고 멈춘다. 한도 초과는 QUOTA_EXHAUSTED다")
    void apply_stopsWithoutLlm() throws Exception {
        FakeAi ai = new FakeAi();
        ai.llmError = "RATE_LIMITED";
        try (Connection connection = dataSource.getConnection()) {
            SummaryBackfill.Result result = SummaryBackfill.apply(connection, SummaryBackfill.plan(connection, options("attractions", 5)), ai);
            assertThat(result.status()).isEqualTo("QUOTA_EXHAUSTED");
            assertThat(jdbc.queryForObject("select count(*) from app.attraction_summaries", Integer.class)).isZero();
        }
    }

    @Test
    @DisplayName("호출 수 상한을 넘겨 계획하지 않는다")
    void plan_respectsMaxCalls() throws Exception {
        for (int i = 0; i < 12; i++) {
            attraction("추가" + i, null, 12);
        }
        try (Connection connection = dataSource.getConnection()) {
            Plan plan = SummaryBackfill.plan(connection, options("attractions", 1));
            assertThat(plan.totalTargets()).isEqualTo(15);
            assertThat(plan.attractions()).hasSize(SummaryBackfill.ATTRACTIONS_PER_CALL);
            assertThat(plan.calls()).isEqualTo(1);
            assertThat(plan.hash()).isEqualTo(SummaryBackfill.plan(connection, options("attractions", 1)).hash());
        }
    }

    @Test
    @DisplayName("지역은 대표 관광지(추천 가능)를 근거로 만들고 근거 관광지 ID를 남긴다")
    void apply_regions() throws Exception {
        FakeAi ai = new FakeAi();
        try (Connection connection = dataSource.getConnection()) {
            Plan plan = SummaryBackfill.plan(connection, options("regions", 5));
            assertThat(plan.regions()).extracting(SummaryBackfill.RegionTarget::sigCd).containsExactly(REGION);
            SummaryBackfill.apply(connection, plan, ai);
            assertThat(ai.requests.get(0)).containsEntry("classCounts", Map.of("NA020900", 1));
            assertThat(jdbc.queryForObject("select basis_attraction_ids::text from app.region_summaries", String.class))
                    .isEqualTo("[" + described + "]");
            assertThat(SummaryBackfill.plan(connection, options("regions", 5)).regions()).isEmpty();
        }
    }

    @Test
    @DisplayName("검수 CSV: 승인·반려·수정을 반영하고, 사전 밖 태그가 있으면 아무것도 쓰지 않는다")
    void exportAndImport() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            SummaryBackfill.apply(connection, SummaryBackfill.plan(connection, options("attractions", 5)), new FakeAi());
            Path out = dir.resolve("drafts.csv");
            assertThat(SummaryBackfill.export(connection, "attractions", out)).isEqualTo(2);

            List<List<String>> rows;
            try (BufferedReader reader = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
                rows = SummaryBackfill.Csv.parse(reader);
            }
            assertThat(rows.get(0)).first().isEqualTo("kind");
            assertThat(rows.get(1).get(4)).isEqualTo("NAME_CATEGORY");   // 이름·분류만 쓴 초안을 먼저 본다

            List<List<String>> reviewed = new ArrayList<>();
            reviewed.add(rows.get(0));
            List<String> approve = new ArrayList<>(rows.get(1));
            approve.set(8, "approve");
            approve.set(9, "다리 위로 \"바람\"이 지나는 곳, 천천히");
            approve.set(10, "#산책 데이트");
            reviewed.add(approve);
            List<String> reject = new ArrayList<>(rows.get(2));
            reject.set(8, "REJECT");
            reject.set(11, "설명과 다름");
            reviewed.add(reject);

            Path bad = dir.resolve("bad.csv");
            List<String> wrongTag = new ArrayList<>(approve);
            wrongTag.set(10, "맛집");
            write(bad, List.of(rows.get(0), wrongTag, reject));
            assertThatThrownBy(() -> SummaryBackfill.importReview(connection, "attractions", bad, "검수자"))
                    .hasMessageContaining("태그 사전에 없는 태그");
            assertThat(jdbc.queryForObject("select count(*) from app.attraction_summaries where status <> 'DRAFT'", Integer.class)).isZero();

            Path good = dir.resolve("reviewed.csv");
            write(good, reviewed);
            SummaryBackfill.Imported imported = SummaryBackfill.importReview(connection, "attractions", good, "검수자");
            assertThat(imported.approved()).isEqualTo(1);
            assertThat(imported.rejected()).isEqualTo(1);

            Map<String, Object> row = jdbc.queryForMap(
                    "select one_line, tags::text as tags, reviewed_by from app.attraction_summaries where attraction_id = ?", nameOnly);
            assertThat(row).containsEntry("one_line", "다리 위로 \"바람\"이 지나는 곳, 천천히")
                    .containsEntry("tags", "[\"산책\", \"데이트\"]")
                    .containsEntry("reviewed_by", "검수자");
            assertThat(jdbc.queryForObject("select status || review_note from app.attraction_summaries where attraction_id = ?", String.class, described))
                    .isEqualTo("REJECTED설명과 다름");

            // 이미 반영한 CSV를 다시 넣으면 DRAFT가 아니라서 바뀌지 않는다
            assertThat(SummaryBackfill.importReview(connection, "attractions", good, "검수자").skippedStale()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("CSV는 따옴표·쉼표·줄바꿈이 든 칸과 BOM을 처리한다")
    void csvRoundTrip() throws Exception {
        List<String> cells = List.of("a,b", "say \"hi\"", "line1\nline2", "", "끝");
        String text = "﻿" + SummaryBackfill.Csv.line(cells) + SummaryBackfill.Csv.line(List.of("x"));
        List<List<String>> rows = SummaryBackfill.Csv.parse(new BufferedReader(new StringReader(text)));
        assertThat(rows).containsExactly(cells, List.of("x"));
    }

    private static void write(Path path, List<List<String>> rows) throws Exception {
        StringBuilder text = new StringBuilder();
        rows.forEach(row -> text.append(SummaryBackfill.Csv.line(row)));
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
