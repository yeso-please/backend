package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 코스 변경 기록(D11), 수단별 이동시간(D5), 관광지 한 줄 소개(T3), AI 코스 문장(D16). */
@SuppressWarnings("unchecked")
class CourseChangeIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Member owner;
    private final List<Long> attractionIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        owner = fixtures.onboardedMember("수정");
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('51150', '강원특별자치도', '강릉시', 37.75, 128.9)");
        for (int i = 0; i < 12; i++) {
            attractionIds.add(attraction("장소" + i, 37.70 + i * 0.005, 128.85 + i * 0.005));
        }
    }

    private Long attraction(String name, double lat, double lng) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr, source_content_id,
                                             lcls_systm1, lcls_systm2, lcls_systm3)
                values (?, '관광지', '51150', '바다를 따라 걷는 길', ?, ?, 12, '강릉시', ?, 'NA', 'NA02', 'NA020900') returning id
                """, Long.class, name, lat, lng, UUID.randomUUID().toString());
        jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                id, "https://image.example/" + id);
        return id;
    }

    private Long trip(Member member) throws Exception {
        Long id = fixtures.createTrip(member.accessToken(), clock.today().plusDays(10), 0);
        jdbc.update("update app.trip_plans set region_id = '51150' where id = ?", id);
        return id;
    }

    private MvcResult generate(Member member, Long tripId, int version) throws Exception {
        return mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private MvcResult edit(Member member, Long tripId, String body) throws Exception {
        return mockMvc.perform(patch("/api/courses/{tripId}/schedule", tripId)
                .header("Authorization", member.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(MvcResult result, String path, Class<T> type) throws Exception {
        return (T) JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    /** 코스에 없는 추천 가능 관광지 하나. */
    private Long notInCourse(MvcResult course) throws Exception {
        List<Integer> used = read(course, "$.days[*].items[?(@.type == 'ATTRACTION')].attractionId");
        return attractionIds.stream().filter(id -> !used.contains(id.intValue())).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("변경 기록")
    class Changes {

        @Test
        @DisplayName("생성 직후에는 모두 기준 추천(BASE)이고 변경 수가 0이다")
        void baseline() throws Exception {
            Long tripId = trip(owner);
            MvcResult course = generate(owner, tripId, 0);
            assertThat(read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].change.origin", List.class))
                    .isNotEmpty().allMatch("BASE"::equals);
            assertThat((Object) read(course, "$.changes.baselineAt")).isNotNull();
            assertThat((Integer) read(course, "$.changes.days[0].added")).isZero();
            assertThat((Object) read(course, "$.changes.latest")).isNull();
        }

        @Test
        @DisplayName("교체·추가·제외를 누가 왜 했는지 남기고, 제외한 장소를 되살리면 기준 추천으로 돌아온다")
        void replaceAddRemoveRestore() throws Exception {
            Member friend = fixtures.onboardedMember("민지");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());
            MvcResult course = generate(owner, tripId, 0);
            String first = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].itemId", List.class).get(0).toString();
            String second = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].itemId", List.class).get(1).toString();
            String firstName = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].name", List.class).get(0).toString();
            String secondName = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].name", List.class).get(1).toString();
            int version = read(course, "$.version");
            Long replacement = notInCourse(course);

            MvcResult edited = edit(friend, tripId, """
                    {"version":%d,"operations":[
                      {"op":"REPLACE","itemId":"%s","attractionId":%d,"note":"노을 보러 가요"},
                      {"op":"REMOVE","itemId":"%s","note":"너무 멀어요"}]}
                    """.formatted(version, first, replacement, second));
            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            List<Map<String, Object>> changes = read(edited, "$.days[0].items[?(@.itemId == '" + first + "')].change");
            Map<String, Object> replaced = changes.get(0);
            assertThat(replaced).containsEntry("origin", "REPLACED").containsEntry("note", "노을 보러 가요");
            assertThat((Map<String, Object>) replaced.get("by")).containsEntry("nickname", "민지");
            assertThat((Map<String, Object>) replaced.get("replacedFrom")).containsEntry("name", firstName);
            assertThat((Integer) read(edited, "$.changes.days[0].replaced")).isEqualTo(1);
            assertThat((Integer) read(edited, "$.changes.days[0].removed")).isEqualTo(1);
            assertThat((String) read(edited, "$.changes.removed[0].name")).isEqualTo(secondName);
            assertThat((String) read(edited, "$.changes.removed[0].note")).isEqualTo("너무 멀어요");
            assertThat((String) read(edited, "$.changes.removed[0].removedBy.nickname")).isEqualTo("민지");
            assertThat((String) read(edited, "$.changes.latest.by.nickname")).isEqualTo("민지");

            int removalId = read(edited, "$.changes.removed[0].removalId");
            MvcResult restored = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"RESTORE","removalId":%d}]}
                    """.formatted((int) read(edited, "$.version"), removalId));
            assertThat(restored.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(restored, "$.changes.removed", List.class)).isEmpty();
            assertThat(read(restored, "$.days[0].items[?(@.name == '" + secondName + "')].change.origin", List.class))
                    .containsExactly("BASE");
            assertThat((String) read(restored, "$.changes.latest.type")).isEqualTo("RESTORED");

            // 이미 되살린 기록은 다시 쓸 수 없다
            MvcResult again = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"RESTORE","removalId":%d}]}
                    """.formatted((int) read(restored, "$.version"), removalId));
            assertThat(again.getResponse().getStatus()).isEqualTo(404);
            assertThat((String) read(again, "$.code")).isEqualTo("COURSE_REMOVAL_NOT_FOUND");
        }

        @Test
        @DisplayName("추가한 장소를 지워도 제외 기록에 남는다(Q6). 재생성하면 기록을 지우고 새 기준점이 된다")
        void addedThenRemovedAndRegenerate() throws Exception {
            Long tripId = trip(owner);
            MvcResult course = generate(owner, tripId, 0);
            MvcResult added = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"ADD","dayIndex":0,"attractionId":%d}]}
                    """.formatted((int) read(course, "$.version"), notInCourse(course)));
            assertThat((Integer) read(added, "$.changes.days[0].added")).isEqualTo(1);
            String addedItem = read(added, "$.days[0].items[?(@.change.origin == 'ADDED')].itemId", List.class).get(0).toString();
            MvcResult removed = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"REMOVE","itemId":"%s"}]}
                    """.formatted((int) read(added, "$.version"), addedItem));
            assertThat((String) read(removed, "$.changes.removed[0].origin")).isEqualTo("ADDED");

            MvcResult regenerated = generate(owner, tripId, read(removed, "$.version"));
            assertThat(read(regenerated, "$.changes.removed", List.class)).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from app.course_removals", Integer.class)).isZero();
        }

        @Test
        @DisplayName("사유는 REMOVE·REPLACE에만, 100자까지다")
        void noteValidation() throws Exception {
            Long tripId = trip(owner);
            MvcResult course = generate(owner, tripId, 0);
            String item = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].itemId", List.class).get(0).toString();
            MvcResult tooLong = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"REMOVE","itemId":"%s","note":"%s"}]}
                    """.formatted((int) read(course, "$.version"), item, "가".repeat(101)));
            assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
            MvcResult wrongOp = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":0,"note":"이유"}]}
                    """.formatted((int) read(course, "$.version"), item));
            assertThat(wrongOp.getResponse().getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("공유 링크 화면에는 변경한 사람 이름이 없고, 초대 링크 둘러보기에는 있다")
        void viewerNames() throws Exception {
            Member friend = fixtures.onboardedMember("서현");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());
            MvcResult course = generate(owner, tripId, 0);
            String item = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].itemId", List.class).get(0).toString();
            edit(friend, tripId, """
                    {"version":%d,"operations":[{"op":"REMOVE","itemId":"%s"}]}
                    """.formatted((int) read(course, "$.version"), item));

            var cookie = fixtures.openShareSession(fixtures.shareLinkToken(owner.accessToken(), tripId));
            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changes.removed[0].removedBy").value(nullValue()))
                    .andExpect(jsonPath("$.tasteBasis").value(nullValue()));

            String invite = fixtures.inviteToken(owner.accessToken(), tripId);
            mockMvc.perform(get("/api/invites/{token}/course", invite))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.myRole").value("VIEWER"))
                    .andExpect(jsonPath("$.changes.removed[0].removedBy.nickname").value("서현"))
                    .andExpect(jsonPath("$.tasteBasis.nickname").value("수정"));
        }
    }

    @Nested
    @DisplayName("항목 표시")
    class Items {

        @Test
        @DisplayName("앞 장소에서의 이동은 도보·자동차·대중교통 세 수단으로 준다. 첫 장소는 null이다")
        void travelByMode() throws Exception {
            Long tripId = trip(owner);
            MvcResult course = generate(owner, tripId, 0);
            assertThat((Object) read(course, "$.days[0].items[0].travelFromPrevious")).isNull();
            List<Map<String, Object>> travels = read(course, "$.days[0].items[?(@.type == 'ATTRACTION' && @.travelFromPrevious != null)].travelFromPrevious");
            assertThat(travels).isNotEmpty();
            Map<String, Object> travel = travels.get(0);
            assertThat((Integer) travel.get("walkMinutes")).isGreaterThanOrEqualTo((Integer) travel.get("carMinutes"));
            assertThat(travel).containsEntry("carSource", "STRAIGHT").containsEntry("estimated", true);
        }

        @Test
        @DisplayName("자동차 시간은 5-7 길찾기로 기억해 둔 도로 시간을 쓴다")
        void carRoadTimeFromRouteCache() throws Exception {
            Long tripId = trip(owner);
            jdbc.update("update app.trip_plans set transport = 'CAR' where id = ?", tripId);
            generate(owner, tripId, 0);
            mockMvc.perform(get("/api/courses/{tripId}/routes", tripId).param("dayIndex", "0").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk());
            MvcResult course = mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", owner.bearer())).andReturn();
            List<String> sources = read(course, "$.days[0].items[?(@.travelFromPrevious != null)].travelFromPrevious.carSource");
            assertThat(sources).contains("ROAD");
        }

        @Test
        @DisplayName("승인된 관광지 한 줄 소개만 코스 항목에 싣는다")
        void approvedOneLine() throws Exception {
            for (Long id : attractionIds) {
                jdbc.update("""
                        insert into app.attraction_summaries (attraction_id, one_line, tags, basis, source_hash, prompt_version,
                                                              status, reviewed_by, reviewed_at)
                        values (?, '바람 따라 걷는 바닷가', '["바다"]'::jsonb, 'SOURCE_SUMMARY', repeat('a', 64), 'summary-v1',
                                'APPROVED', '검수자', now())
                        """, id);
            }
            Long tripId = trip(owner);
            MvcResult course = generate(owner, tripId, 0);
            assertThat(read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].oneLine", List.class))
                    .isNotEmpty().allMatch("바람 따라 걷는 바닷가"::equals);
            assertThat(read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].summaryBasis", List.class))
                    .allMatch("SOURCE_SUMMARY"::equals);
        }
    }

    @Nested
    @DisplayName("AI 코스 문장(D16)")
    class Explanations {

        @Test
        @DisplayName("ai가 없으면 규칙 제목·이유 그대로이고 aiGenerated는 false다")
        void withoutAi() throws Exception {
            MvcResult course = generate(owner, trip(owner), 0);
            assertThat((String) read(course, "$.titleSource")).isEqualTo("RULE");
            assertThat((Object) read(course, "$.intro")).isNull();
            assertThat((Boolean) read(course, "$.aiGenerated")).isFalse();
        }

        @Test
        @DisplayName("ai 문장을 붙여도 여행 버전은 그대로라 받은 version으로 바로 편집할 수 있다. 본인 이유는 취향 기준 회원에게만 보인다")
        void withAi() throws Exception {
            fakeAiTextClient.respond(true);
            Member friend = fixtures.onboardedMember("친구");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());
            MvcResult course = generate(owner, tripId, 0);
            assertThat((String) read(course, "$.titleSource")).isEqualTo("LLM");
            assertThat((String) read(course, "$.title")).isEqualTo("AI가 지은 강원특별자치도 강릉시 여행");
            assertThat((String) read(course, "$.intro")).isEqualTo("AI가 쓴 소개글이에요.");
            assertThat((Boolean) read(course, "$.aiGenerated")).isTrue();
            assertThat(read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].reasonSource", List.class)).allMatch("LLM"::equals);
            assertThat(read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].personalReason", List.class))
                    .allMatch("좋아하신 여행과 비슷해요"::equals);

            MvcResult friendView = mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", friend.bearer())).andReturn();
            assertThat(read(friendView, "$.days[0].items[?(@.type == 'ATTRACTION')].personalReason", List.class)).allMatch(v -> v == null);

            int version = read(course, "$.version");
            String item = read(course, "$.days[0].items[?(@.type == 'ATTRACTION')].itemId", List.class).get(0).toString();
            MvcResult moved = edit(owner, tripId, """
                    {"version":%d,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":0}]}
                    """.formatted(version, item));
            assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        }
    }
}
