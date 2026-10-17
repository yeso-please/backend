package com.yeso.backend.migration;

import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 지역 대표 사진 후보 적재와 검수 CSV. */
class RegionPhotoBackfillIntegrationTest extends IntegrationTest {

    private static final String REGION = "51150";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into app.regions (sig_cd, province, city) values (?, '강원특별자치도', '강릉시')", REGION);
    }

    private Path candidates() throws Exception {
        Path in = dir.resolve("candidates.jsonl");
        Files.writeString(in, String.join("\n",
                "{\"sigCd\": \"51150\", \"candidates\": ["
                        + "{\"galContentId\": \"1\", \"title\": \"정동진해변\", \"imageUrl\": \"https://tong.visitkorea.or.kr/cms2/website/1.jpg\","
                        + " \"photographer\": \"한국관광공사 김지호\", \"location\": \"강원도 강릉시 강동면\", \"month\": \"201510\"},"
                        + "{\"galContentId\": \"2\", \"title\": \"주문진항\", \"imageUrl\": \"https://tong.visitkorea.or.kr/cms2/website/2.jpg\","
                        + " \"photographer\": \"한국관광공사 이범수\", \"location\": \"강원도 강릉시\", \"month\": \"2015\"},"
                        + "{\"galContentId\": \"3\", \"title\": \"다른 서버\", \"imageUrl\": \"http://evil.example/3.jpg\"}]}",
                "{\"sigCd\": \"99999\", \"candidates\": [{\"galContentId\": \"9\", \"imageUrl\": \"https://tong.visitkorea.or.kr/9.jpg\"}]}",
                ""));
        return in;
    }

    @Test
    @DisplayName("후보는 관광공사 이미지 서버의 https 주소만 DRAFT로 넣고, 다시 넣어도 늘지 않는다")
    void importCandidates() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            RegionPhotoBackfill.Loaded loaded = RegionPhotoBackfill.importCandidates(connection, candidates());
            assertThat(loaded).isEqualTo(new RegionPhotoBackfill.Loaded(2, 1, 1));
            RegionPhotoBackfill.importCandidates(connection, candidates());
        }
        assertThat(jdbc.queryForObject("select count(*) from app.region_hero_photos where status = 'DRAFT'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select photography_month from app.region_hero_photos where source_content_id = '2'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("select license_note from app.region_hero_photos where source_content_id = '1'", String.class))
                .isEqualTo("공공누리 제1유형");
    }

    @Test
    @DisplayName("검수 CSV로 지역마다 한 장을 승인하고, 다시 승인하면 이전 승인 사진은 REJECTED가 된다")
    void exportAndImport() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            RegionPhotoBackfill.importCandidates(connection, candidates());
            Path out = dir.resolve("photos.csv");
            assertThat(RegionPhotoBackfill.export(connection, out)).isEqualTo(2);
            List<List<String>> rows = read(out);
            assertThat(rows.get(0)).isEqualTo(RegionPhotoBackfill.CSV_HEADER);
            assertThat(rows.get(1).subList(0, 5)).containsExactly("51150", "강원특별자치도 강릉시", "1", "1", "정동진해변");

            Path twice = dir.resolve("twice.csv");
            write(twice, List.of(RegionPhotoBackfill.CSV_HEADER, decided(rows.get(1), "APPROVE"), decided(rows.get(2), "approve")));
            assertThatThrownBy(() -> RegionPhotoBackfill.importReview(connection, twice, "검수자"))
                    .hasMessageContaining("지역마다 한 장");
            assertThat(jdbc.queryForObject("select count(*) from app.region_hero_photos where status = 'DRAFT'", Integer.class)).isEqualTo(2);

            Path first = dir.resolve("first.csv");
            write(first, List.of(RegionPhotoBackfill.CSV_HEADER, decided(rows.get(1), "APPROVE")));
            assertThat(RegionPhotoBackfill.importReview(connection, first, "검수자"))
                    .isEqualTo(new RegionPhotoBackfill.Imported(1, 0, 0, 0));

            Path second = dir.resolve("second.csv");
            write(second, List.of(RegionPhotoBackfill.CSV_HEADER, decided(rows.get(2), "APPROVE"), decided(rows.get(1), "REJECT")));
            assertThat(RegionPhotoBackfill.importReview(connection, second, "검수자"))
                    .isEqualTo(new RegionPhotoBackfill.Imported(1, 0, 1, 0));
        }
        assertThat(jdbc.queryForList("select source_content_id from app.region_hero_photos where status = 'APPROVED'", String.class))
                .containsExactly("2");
        assertThat(jdbc.queryForObject("select status from app.region_hero_photos where source_content_id = '1'", String.class))
                .isEqualTo("REJECTED");
    }

    @Test
    void allowedImage() {
        assertThat(RegionPhotoBackfill.allowedImage("https://tong.visitkorea.or.kr/a.jpg")).isTrue();
        assertThat(RegionPhotoBackfill.allowedImage("http://tong.visitkorea.or.kr/a.jpg")).isFalse();
        assertThat(RegionPhotoBackfill.allowedImage("https://tong.visitkorea.or.kr.evil.example/a.jpg")).isFalse();
        assertThat(RegionPhotoBackfill.allowedImage("not a url")).isFalse();
    }

    private static List<String> decided(List<String> row, String decision) {
        List<String> copy = new ArrayList<>(row);
        copy.set(RegionPhotoBackfill.CSV_HEADER.indexOf("decision"), decision);
        return copy;
    }

    private static List<List<String>> read(Path path) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return SummaryBackfill.Csv.parse(reader);
        }
    }

    private static void write(Path path, List<List<String>> rows) throws Exception {
        StringBuilder text = new StringBuilder();
        rows.forEach(row -> text.append(SummaryBackfill.Csv.line(row)));
        Files.writeString(path, text.toString());
    }
}
