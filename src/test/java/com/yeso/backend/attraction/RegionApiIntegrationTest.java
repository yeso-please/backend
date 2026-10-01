package com.yeso.backend.attraction;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 지역·관광지 조회(docs/api/attraction.md 7-1~7-4). 데이터는 SQL로 준비한다. */
class RegionApiIntegrationTest extends IntegrationTest {

    private static final String GYEONGJU = "47130";
    private static final String JONGNO = "11110";

    @Autowired
    private JdbcTemplate jdbc;

    private Member member;

    private int sourceSeq = 10;

    @BeforeEach
    void setUp() throws Exception {
        member = fixtures.signup();
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values (?, '경상북도', '경주시', 35.856, 129.225)", GYEONGJU);
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values (?, '서울특별시', '종로구', 37.573, 126.979)", JONGNO);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", member.bearer());
    }

    private Long attraction(String name, Double lat, Double lng, String description, Integer contentType, String imageStatus) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr,
                                             source_content_id, use_time, rest_date)
                values (?, '관광지', ?, ?, ?, ?, ?, '경북 경주시', ?, '09:00~22:00', '연중무휴') returning id
                """, Long.class, name, GYEONGJU, description, lat, lng, contentType, "1262" + (sourceSeq++));
        if (imageStatus != null) {
            jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status, license_note) values (?, ?, ?, '공공누리 1유형')",
                    id, "https://img.example/" + id + ".jpg", imageStatus);
        }
        return id;
    }

    private Long recommendable(String name) {
        return attraction(name, 35.84, 129.21, "설명", 12, "VALID");
    }

    private void approvedContent(String heroStatus, String landmarksJson) {
        jdbc.update("""
                insert into app.region_contents (region_id, title, introduction, history_tags, hero_image_url,
                    hero_image_source_name, hero_image_source_url, hero_image_license_note, hero_image_validation_status,
                    status, prompt_version, reviewed_at, characteristics, landmarks, sources)
                values (?, '천년의 시간이 머무는 도시', E'첫 문단\\n\\n  둘째 문단  \\n\\n', '신라의 수도, 불교 문화',
                    'https://img.example/hero.jpg', '한국관광공사', 'https://src.example', '공공누리 1유형', ?,
                    'APPROVED', 1, now(), '["역사 유적","야경"]'::jsonb, cast(? as jsonb),
                    '[{"title":"한국관광공사 TourAPI","url":"https://api.example"}]'::jsonb)
                """, GYEONGJU, heroStatus, landmarksJson);
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰 없이 부르면 401 AUTH_UNAUTHENTICATED다")
        void withoutToken() throws Exception {
            for (String path : List.of("/api/regions", "/api/regions/47130/card", "/api/regions/47130/attractions", "/api/attractions/1")) {
                mockMvc.perform(get(path))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            }
        }
    }

    @Nested
    @DisplayName("7-1 지역 목록")
    class ListRegions {

        @Test
        @DisplayName("days 없이 부르면 전체 지역을 주고 추첨 필드는 null이다")
        void withoutDays() throws Exception {
            mockMvc.perform(authed(get("/api/regions")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.days").isEmpty())
                    .andExpect(jsonPath("$.eligibleCount").isEmpty())
                    .andExpect(jsonPath("$.regions.length()").value(2))
                    .andExpect(jsonPath("$.regions[0].sigCd").value(JONGNO))
                    .andExpect(jsonPath("$.regions[0].centerLat").value(37.573))
                    .andExpect(jsonPath("$.regions[0].drawEligible").isEmpty())
                    .andExpect(jsonPath("$.regions[0].ineligibleReasons").isEmpty());
        }

        @Test
        @DisplayName("days를 주면 지역별 추첨 가능 여부와 사유, 가능 지역 수를 준다. 밀도를 빼면 RELAXED다")
        void withDays() throws Exception {
            approvedContent("VALID", "[]");
            for (int i = 0; i < 5; i++) {
                recommendable("관광지" + i);
            }

            mockMvc.perform(authed(get("/api/regions").param("days", "1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.days").value(1))
                    .andExpect(jsonPath("$.scheduleDensity").value("RELAXED"))
                    .andExpect(jsonPath("$.eligibleCount").value(1))
                    .andExpect(jsonPath("$.regions[1].sigCd").value(GYEONGJU))
                    .andExpect(jsonPath("$.regions[1].drawEligible").value(true))
                    .andExpect(jsonPath("$.regions[0].drawEligible").value(false))
                    .andExpect(jsonPath("$.regions[0].ineligibleReasons",
                            contains("INSUFFICIENT_ATTRACTIONS")));

            mockMvc.perform(authed(get("/api/regions").param("days", "1").param("scheduleDensity", "PACKED")))
                    .andExpect(jsonPath("$.eligibleCount").value(0));
        }

        @Test
        @DisplayName("days가 1~7 밖이면 400 REGION_INVALID_DAYS, 밀도가 틀리면 400 COMMON_INVALID_REQUEST다")
        void invalidParams() throws Exception {
            for (String days : List.of("0", "8")) {
                mockMvc.perform(authed(get("/api/regions").param("days", days)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("REGION_INVALID_DAYS"));
            }
            mockMvc.perform(authed(get("/api/regions").param("days", "1").param("scheduleDensity", "FAST")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("7-2 지역 카드")
    class RegionCard {

        @Test
        @DisplayName("승인된 소개를 문단·태그·대표 관광지·출처로 나눠 준다")
        void success() throws Exception {
            Long first = recommendable("대릉원");
            Long second = recommendable("첨성대");
            Long broken = attraction("사진 없는 곳", 35.84, 129.21, "설명", 12, "INVALID");
            approvedContent("VALID", """
                    [{"attractionId": %d, "order": 2}, {"attractionId": %d, "order": 1}, {"attractionId": %d, "order": 3}]
                    """.formatted(first, second, broken));

            mockMvc.perform(authed(get("/api/regions/{sigCd}/card", GYEONGJU)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.city").value("경주시"))
                    .andExpect(jsonPath("$.title").value("천년의 시간이 머무는 도시"))
                    .andExpect(jsonPath("$.introduction", contains("첫 문단", "둘째 문단")))
                    .andExpect(jsonPath("$.heroImage.sourceName").value("한국관광공사"))
                    .andExpect(jsonPath("$.heroImage.license").value("공공누리 1유형"))
                    .andExpect(jsonPath("$.characteristics", contains("역사 유적", "야경")))
                    .andExpect(jsonPath("$.historyHighlights", contains("신라의 수도", "불교 문화")))
                    .andExpect(jsonPath("$.landmarks.length()").value(2))
                    .andExpect(jsonPath("$.landmarks[0].name").value("첨성대"))
                    .andExpect(jsonPath("$.landmarks[1].name").value("대릉원"))
                    .andExpect(jsonPath("$.sources[0].name").value("한국관광공사 TourAPI"));
        }

        @Test
        @DisplayName("소개 콘텐츠가 없어도 지역명 기반 카드가 반환된다")
        void contentOptional() throws Exception {
            mockMvc.perform(authed(get("/api/regions/{sigCd}/card", GYEONGJU)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("경주시"))
                    .andExpect(jsonPath("$.introduction.length()").value(0))
                    .andExpect(jsonPath("$.heroImage").value(nullValue()));

            approvedContent("PENDING", "[]");
            mockMvc.perform(authed(get("/api/regions/{sigCd}/card", GYEONGJU)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("천년의 시간이 머무는 도시"))
                    .andExpect(jsonPath("$.heroImage").value(nullValue()));
        }

        @Test
        @DisplayName("없는 지역이면 404 REGION_NOT_FOUND다")
        void unknownRegion() throws Exception {
            mockMvc.perform(authed(get("/api/regions/{sigCd}/card", "99999")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("7-3 지도 관광지 핀")
    class Pins {

        @Test
        @DisplayName("코스에 못 넣는 곳은 흐린 핀으로 주고, 좌표 없는 곳과 쇼핑·숙박·음식점은 뺀다")
        void includesNotRecommendable() throws Exception {
            Long good = recommendable("대릉원");
            Long noImage = attraction("사진 없는 곳", 35.84, 129.21, "설명", 12, null);
            attraction("좌표 없는 곳", null, null, "설명", 12, "VALID");
            attraction("식당", 35.84, 129.21, "설명", 39, "VALID");
            attraction("호텔", 35.84, 129.21, "설명", 32, "VALID");

            mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", GYEONGJU)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].attractionId").value(good))
                    .andExpect(jsonPath("$.items[0].recommendable").value(true))
                    .andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://img.example/" + good + ".jpg"))
                    .andExpect(jsonPath("$.items[1].attractionId").value(noImage))
                    .andExpect(jsonPath("$.items[1].recommendable").value(false))
                    .andExpect(jsonPath("$.nextCursor").isEmpty());
        }

        @Test
        @DisplayName("지도 영역과 유형으로 거른다")
        void filters() throws Exception {
            Long inside = recommendable("안쪽");
            attraction("바깥쪽", 35.95, 129.40, "설명", 12, "VALID");
            Long museum = attraction("박물관", 35.84, 129.21, "설명", 14, "VALID");

            mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", GYEONGJU).param("bbox", "129.15,35.78,129.30,35.90")))
                    .andExpect(jsonPath("$.items[*].attractionId", contains(inside.intValue(), museum.intValue())));
            mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", GYEONGJU).param("category", "HISTORY_CULTURE")))
                    .andExpect(jsonPath("$.items[*].attractionId", contains(museum.intValue())));
        }

        @Test
        @DisplayName("nextCursor로 다음 페이지를 이어서 받는다")
        void pagination() throws Exception {
            Long a = recommendable("1");
            Long b = recommendable("2");
            Long c = recommendable("3");

            String firstPage = mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", GYEONGJU).param("limit", "2")))
                    .andExpect(jsonPath("$.items[*].attractionId", contains(a.intValue(), b.intValue())))
                    .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(firstPage, "$.nextCursor");

            mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", GYEONGJU).param("limit", "2").param("cursor", cursor)))
                    .andExpect(jsonPath("$.items[*].attractionId", contains(c.intValue())))
                    .andExpect(jsonPath("$.nextCursor").isEmpty());
        }

        @Test
        @DisplayName("영역이 틀리면 400 ATTRACTION_INVALID_BOUNDS다")
        void invalidBounds() throws Exception {
            for (String bbox : List.of("1,2,3", "a,b,c,d", "129.30,35.78,129.15,35.90", "100,35,129,36")) {
                mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", GYEONGJU).param("bbox", bbox)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("ATTRACTION_INVALID_BOUNDS"));
            }
        }

        @Test
        @DisplayName("유형·limit·cursor가 틀리면 400 COMMON_INVALID_REQUEST, 없는 지역은 404 REGION_NOT_FOUND다")
        void invalidParams() throws Exception {
            List<MockHttpServletRequestBuilder> bad = List.of(
                    get("/api/regions/{sigCd}/attractions", GYEONGJU).param("category", "FOOD"),
                    get("/api/regions/{sigCd}/attractions", GYEONGJU).param("limit", "0"),
                    get("/api/regions/{sigCd}/attractions", GYEONGJU).param("limit", "201"),
                    get("/api/regions/{sigCd}/attractions", GYEONGJU).param("cursor", "forged!"));
            for (MockHttpServletRequestBuilder request : bad) {
                mockMvc.perform(authed(request))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
            }
            mockMvc.perform(authed(get("/api/regions/{sigCd}/attractions", "99999")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("7-4 관광지 상세")
    class Detail {

        @Test
        @DisplayName("추천 가능 관광지의 상세와 검증된 이미지, 출처를 준다")
        void success() throws Exception {
            Long id = attraction("박물관", 35.84, 129.21, "신라 유물", 14, "VALID");
            jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, 'https://img.example/pending.jpg', 'PENDING')", id);

            mockMvc.perform(authed(get("/api/attractions/{id}", id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionSigCd").value(GYEONGJU))
                    .andExpect(jsonPath("$.category").value("HISTORY_CULTURE"))
                    .andExpect(jsonPath("$.estimatedDurationMinutes").value(120))
                    .andExpect(jsonPath("$.estimated").value(true))
                    .andExpect(jsonPath("$.useTime").value("09:00~22:00"))
                    .andExpect(jsonPath("$.images.length()").value(1))
                    .andExpect(jsonPath("$.images[0].sourceName").value("한국관광공사"))
                    .andExpect(jsonPath("$.images[0].license").value("공공누리 1유형"))
                    .andExpect(jsonPath("$.recommendable").value(true))
                    .andExpect(jsonPath("$.notRecommendableReasons.length()").value(0))
                    .andExpect(jsonPath("$.sources[0].name").value("한국관광공사 TourAPI"))
                    .andExpect(jsonPath("$.sources[0].contentId").value(org.hamcrest.Matchers.startsWith("1262")));
        }

        @Test
        @DisplayName("코스에 못 넣는 이유를 모두 준다")
        void reasons() throws Exception {
            Long id = attraction("빈 곳", null, null, " ", 12, null);
            jdbc.update("insert into app.data_quality_issues (entity_type, entity_key, issue_type, severity) values ('ATTRACTION', ?, 'BROKEN', 'ERROR')",
                    String.valueOf(id));

            mockMvc.perform(authed(get("/api/attractions/{id}", id)))
                    .andExpect(jsonPath("$.recommendable").value(false))
                    .andExpect(jsonPath("$.notRecommendableReasons",
                            contains("MISSING_DESCRIPTION", "MISSING_IMAGE", "MISSING_COORDINATE", "QUALITY_ISSUE")));
        }

        @Test
        @DisplayName("없는 관광지와 쇼핑·숙박·음식점은 404 ATTRACTION_NOT_FOUND다")
        void notFound() throws Exception {
            Long shop = attraction("쇼핑몰", 35.84, 129.21, "설명", 38, "VALID");
            for (Long id : List.of(999_999L, shop)) {
                mockMvc.perform(authed(get("/api/attractions/{id}", id)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("ATTRACTION_NOT_FOUND"));
            }
        }
    }
}
