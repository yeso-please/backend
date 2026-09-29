package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.shared.embedding.VectorCodec;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 코스 생성·조회(docs/api/trip.md 5-1·5-2)와 공유 코스 조회(4-13). 지역·관광지는 SQL로 준비한다. */
class CourseApiIntegrationTest extends IntegrationTest {

    private static final String GYEONGJU = "47130";

    @Autowired
    private JdbcTemplate jdbc;

    private Member creator;
    private final List<Long> attractionIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        creator = fixtures.onboardedMember("만든사람");
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values (?, '경상북도', '경주시', 35.856, 129.225)", GYEONGJU);
        for (int i = 0; i < 12; i++) {
            attractionIds.add(attraction(35.80 + i * 0.005, 129.20 + i * 0.005));
        }
    }

    private Long attraction(double lat, double lng) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr, source_content_id)
                values (?, '관광지', ?, '설명', ?, ?, 14, '경북 경주시', ?) returning id
                """, Long.class, "관광지" + attractionIds.size(), GYEONGJU, lat, lng, "c" + System.nanoTime());
        jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                id, "https://img.example/" + id + ".jpg");
        return id;
    }

    /** 1박 2일 여행을 만들고 경주로 지역을 정한다(지역 정하기 3-7 대신 SQL). */
    private Long tripInGyeongju(Member owner) throws Exception {
        Long tripId = fixtures.createTrip(owner.accessToken(), clock.today().plusDays(10), 1);
        jdbc.update("update app.trip_plans set region_id = ? where id = ?", GYEONGJU, tripId);
        return tripId;
    }

    private MvcResult generate(Member member, Long tripId, String body) throws Exception {
        return mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private int version(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.version");
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰 없이 부르면 401 AUTH_UNAUTHENTICATED다")
        void withoutToken() throws Exception {
            mockMvc.perform(post("/api/courses/1/generate").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            mockMvc.perform(get("/api/courses/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        }
    }

    @Nested
    @DisplayName("5-1 코스 생성·재생성")
    class Generate {

        @Test
        @DisplayName("만든 사람이 만들면 201과 날짜별 순서 목록을 받고 여행 버전이 오른다")
        void creatorGenerates() throws Exception {
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.myRole").value("PARTICIPANT"))
                    .andExpect(jsonPath("$.regionName").value("경상북도 경주시"))
                    .andExpect(jsonPath("$.scheduleDensity").value("RELAXED"))
                    .andExpect(jsonPath("$.title").value("경주, 역사를 따라 걷는 2일"))
                    .andExpect(jsonPath("$.titleSource").value("RULE"))
                    .andExpect(jsonPath("$.recommendationMode").value("RULE_BASED"))
                    .andExpect(jsonPath("$.tasteBasis.nickname").value("만든사람"))
                    .andExpect(jsonPath("$.days.length()").value(2))
                    .andExpect(jsonPath("$.days[0].date").value(clock.today().plusDays(10).toString()))
                    .andExpect(jsonPath("$.days[0].items.length()").value(6))
                    .andExpect(jsonPath("$.days[0].items[0].type").value("ATTRACTION"))
                    .andExpect(jsonPath("$.days[0].items[0].itemId").value(org.hamcrest.Matchers.startsWith("a-")))
                    .andExpect(jsonPath("$.days[0].items[0].category").value("HISTORY_CULTURE"))
                    .andExpect(jsonPath("$.days[0].items[0].thumbnailUrl").isNotEmpty())
                    .andExpect(jsonPath("$.days[0].items[0].travelFromPreviousMinutes").isEmpty())
                    .andExpect(jsonPath("$.days[0].items[2].type").value("MEAL"))
                    .andExpect(jsonPath("$.days[0].items[2].meal").value("LUNCH"))
                    .andExpect(jsonPath("$.days[0].items[2].restaurant").isEmpty())
                    .andExpect(jsonPath("$.days[0].items[5].meal").value("DINNER"))
                    .andExpect(jsonPath("$.warnings[*].code", hasItem("ROUTE_TIME_ESTIMATED")))
                    .andExpect(jsonPath("$.warnings[*].code", hasItem("PERSONALIZATION_FALLBACK")));

            mockMvc.perform(get("/api/trips").header("Authorization", creator.bearer()))
                    .andExpect(jsonPath("$[0].hasCourse").value(true))
                    .andExpect(jsonPath("$[0].title").value("경주, 역사를 따라 걷는 2일"));
        }

        @Test
        @DisplayName("첫 코스는 만든 사람만 만들고(403 COURSE_CREATOR_ONLY), 그 뒤 재생성은 참여자 누구나 한다")
        void creatorOnlyThenAnyone() throws Exception {
            Long tripId = tripInGyeongju(creator);
            Member friend = fixtures.onboardedMember("여행친구");
            fixtures.joinByInvite(creator.accessToken(), tripId, friend.accessToken());

            MvcResult denied = generate(friend, tripId, "{\"version\":0}");
            assertThat(denied.getResponse().getStatus()).isEqualTo(403);
            assertThat((String) JsonPath.read(denied.getResponse().getContentAsString(), "$.code")).isEqualTo("COURSE_CREATOR_ONLY");

            generate(creator, tripId, "{\"version\":0}");
            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", friend.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.tasteBasis.nickname").value("여행친구"));
        }

        @Test
        @DisplayName("만든 사람이 탈퇴했으면 남은 참여자가 첫 코스를 만든다")
        void creatorLeft() throws Exception {
            Long tripId = tripInGyeongju(creator);
            Member friend = fixtures.onboardedMember("여행친구");
            fixtures.joinByInvite(creator.accessToken(), tripId, friend.accessToken());
            mockMvc.perform(delete("/api/trips/{tripId}/participants/me", tripId).header("Authorization", creator.bearer()));

            assertThat(generate(friend, tripId, "{\"version\":0}").getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("같은 조건으로 다시 만들면 항목이 새 ID로 바뀌고 고른 식당은 사라진다")
        void regenerateReplaces() throws Exception {
            Long tripId = tripInGyeongju(creator);
            MvcResult first = generate(creator, tripId, "{\"version\":0}");
            String lunchId = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[2].itemId");
            jdbc.update("insert into app.course_meal_restaurants (course_item_id, provider, external_id, name, selected_at) values (?, 'KAKAO', '1', '식당', now())",
                    Long.valueOf(lunchId.substring(2)));

            MvcResult second = generate(creator, tripId, "{\"version\":1}");

            assertThat((String) JsonPath.read(second.getResponse().getContentAsString(), "$.days[0].items[2].itemId")).isNotEqualTo(lunchId);
            assertThat(jdbc.queryForObject("select count(*) from app.course_meal_restaurants", Integer.class)).isZero();
        }

        @Test
        @DisplayName("밀도를 보내면 그 밀도로 만들고 여행 밀도도 바뀐다. 다른 값이면 400 COMMON_INVALID_REQUEST다")
        void density() throws Exception {
            Long tripId = tripInGyeongju(creator);

            MvcResult result = generate(creator, tripId, "{\"version\":0,\"scheduleDensity\":\"PACKED\"}");
            assertThat((Integer) JsonPath.read(result.getResponse().getContentAsString(), "$.days[0].items.length()")).isEqualTo(8);
            mockMvc.perform(get("/api/trips/{tripId}/context", tripId).header("Authorization", creator.bearer()))
                    .andExpect(jsonPath("$.scheduleDensity").value("PACKED"));

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1,\"scheduleDensity\":\"FAST\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }

        @Test
        @DisplayName("version을 빼면 400, 다르면 409 TRIP_VERSION_CONFLICT다")
        void versionRules() throws Exception {
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":5}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("지역을 정하기 전이면 409 TRIP_REGION_NOT_SELECTED, 끝난 여행이면 409 TRIP_ENDED다")
        void notReady() throws Exception {
            Long noRegion = fixtures.createTrip(creator.accessToken(), clock.today().plusDays(40), 1);
            mockMvc.perform(post("/api/courses/{tripId}/generate", noRegion)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_REGION_NOT_SELECTED"));

            Long tripId = tripInGyeongju(creator);
            clock.advance(Duration.ofDays(12));
            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_ENDED"));
        }

        @Test
        @DisplayName("하루 1곳도 못 채우면 422 COURSE_INSUFFICIENT_CANDIDATES와 모자란 날을 준다")
        void insufficient() throws Exception {
            jdbc.update("update app.attraction_images set validation_status = 'INVALID' where attraction_id <> ?", attractionIds.get(0));
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("COURSE_INSUFFICIENT_CANDIDATES"))
                    .andExpect(jsonPath("$.details.days[0].dayIndex").value(1))
                    .andExpect(jsonPath("$.details.days[0].available").value(0));
        }

        @Test
        @DisplayName("요청자와 관광지의 취향 벡터가 있으면 취향 반영(PERSONALIZED)으로 만든다")
        void personalized() throws Exception {
            float[] requesterVector = new float[384];
            requesterVector[0] = 1f;
            jdbc.update("update app.user_taste_vectors set embedding = ?, dimension = 384 where user_id = ?",
                    VectorCodec.encode(requesterVector), creator.userId());
            for (Long id : attractionIds) {
                float[] attractionVector = new float[384];
                attractionVector[0] = 1f;
                attractionVector[1] = (float) (id % 5) / 10;
                jdbc.update("""
                        insert into app.attraction_embeddings (attraction_id, embedding, dimension, model_version, template_version)
                        values (?, ?, 384, 'mminilm-l12-v1', 2)
                        """, id, VectorCodec.encode(attractionVector));
            }
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.recommendationMode").value("PERSONALIZED"))
                    .andExpect(jsonPath("$.warnings[*].code", not(hasItem("PERSONALIZATION_FALLBACK"))));
        }

        @Test
        @DisplayName("취향이 없고 공식 코스가 있으면 공식 코스(TOUR_OFFICIAL)로 만든다")
        void tourOfficial() throws Exception {
            Long courseId = jdbc.queryForObject("""
                    insert into app.official_courses (region_id, source_content_id, title) values (?, 'oc1', '신라 역사 탐방') returning id
                    """, Long.class, GYEONGJU);
            jdbc.update("insert into app.official_course_stops (official_course_id, stop_order, attraction_id, name) values (?, 0, ?, '첫 장소')",
                    courseId, attractionIds.get(3));
            Long tripId = tripInGyeongju(creator);

            MvcResult result = generate(creator, tripId, "{\"version\":0}");

            String body = result.getResponse().getContentAsString();
            assertThat((String) JsonPath.read(body, "$.recommendationMode")).isEqualTo("TOUR_OFFICIAL");
            List<String> reasons = JsonPath.read(body, "$.days[*].items[?(@.attractionId == " + attractionIds.get(3) + ")].reason");
            assertThat(reasons).containsExactly("관광공사 추천 코스 「신라 역사 탐방」에 나오는 곳이에요");
        }
    }

    @Nested
    @DisplayName("취향 반영 방식")
    class TasteMode {

        @Test
        @DisplayName("프로필에서 완전 랜덤을 골랐으면 공식 코스가 있어도 RANDOM으로 만든다")
        void profileRandom() throws Exception {
            Long courseId = jdbc.queryForObject(
                    "insert into app.official_courses (region_id, source_content_id, title) values (?, 'oc2', '공식') returning id",
                    Long.class, GYEONGJU);
            jdbc.update("insert into app.official_course_stops (official_course_id, stop_order, attraction_id, name) values (?, 0, ?, '장소')",
                    courseId, attractionIds.get(0));
            mockMvc.perform(patch("/api/me/preferences")
                    .header("Authorization", creator.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"courseTasteMode\":\"RANDOM\"}"));
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.recommendationMode").value("RANDOM"))
                    .andExpect(jsonPath("$.warnings[*].code", not(hasItem("PERSONALIZATION_FALLBACK"))));
        }

        @Test
        @DisplayName("요청의 tasteMode가 프로필 설정보다 우선하고, 다른 값이면 400 COMMON_INVALID_REQUEST다")
        void requestOverrides() throws Exception {
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0,\"tasteMode\":\"RANDOM\"}"))
                    .andExpect(jsonPath("$.recommendationMode").value("RANDOM"));
            mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                            .header("Authorization", creator.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1,\"tasteMode\":\"SOMETIMES\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("5-2 코스 조회")
    class GetCourse {

        @Test
        @DisplayName("만들기 전이면 404 COURSE_NOT_FOUND, 참여자가 아니면 404 TRIP_NOT_FOUND다")
        void notFound() throws Exception {
            Long tripId = tripInGyeongju(creator);

            mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", creator.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
            mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", fixtures.onboardedMember().bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("만든 코스를 그대로 보여주고, 그 뒤 추천 대상에서 빠진 관광지에는 경고를 붙인다")
        void showsCourseWithCurrentData() throws Exception {
            Long tripId = tripInGyeongju(creator);
            MvcResult created = generate(creator, tripId, "{\"version\":0}");
            Integer firstAttraction = JsonPath.read(created.getResponse().getContentAsString(), "$.days[0].items[0].attractionId");
            String firstItemId = JsonPath.read(created.getResponse().getContentAsString(), "$.days[0].items[0].itemId");
            jdbc.update("update app.attractions set name = '새 이름' where id = ?", firstAttraction.longValue());
            jdbc.update("update app.attraction_images set validation_status = 'INVALID' where attraction_id = ?", firstAttraction.longValue());

            mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", creator.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.days[0].items[0].name").value("새 이름"))
                    .andExpect(jsonPath("$.warnings[?(@.code == 'ATTRACTION_NO_LONGER_RECOMMENDABLE')].itemId", hasItem(firstItemId)));
        }
    }

    @Nested
    @DisplayName("4-13 공유 코스 조회")
    class Shared {

        @Test
        @DisplayName("공유 열람자는 VIEWER로 코스를 보고 참여자 이름은 보이지 않는다. 코스가 없으면 days가 비어 있다")
        void viewerSeesCourse() throws Exception {
            Long tripId = tripInGyeongju(creator);
            Cookie session = fixtures.openShareSession(fixtures.shareLinkToken(creator.accessToken(), tripId));

            mockMvc.perform(get("/api/shared/courses").cookie(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.myRole").value("VIEWER"))
                    .andExpect(jsonPath("$.regionSigCd").value(GYEONGJU))
                    .andExpect(jsonPath("$.title").isEmpty())
                    .andExpect(jsonPath("$.days.length()").value(0))
                    .andExpect(jsonPath("$.warnings.length()").value(0));

            generate(creator, tripId, "{\"version\":0}");
            mockMvc.perform(get("/api/shared/courses").cookie(session))
                    .andExpect(jsonPath("$.days.length()").value(2))
                    .andExpect(jsonPath("$.days[0].items[0].name").isNotEmpty())
                    .andExpect(jsonPath("$.tasteBasis").isEmpty())
                    .andExpect(jsonPath("$.updatedBy").isEmpty());
        }
    }
}
