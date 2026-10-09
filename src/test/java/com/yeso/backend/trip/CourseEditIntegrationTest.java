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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseEditIntegrationTest extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    private Member member;
    private final List<Long> attractionIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        member = fixtures.onboardedMember("일정편집자");
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47130', '경상북도', '경주시', 35.856, 129.225)");
        for (int i = 0; i < 12; i++) {
            attractionIds.add(attraction(i == 11 ? "추가 장소" : "관광지" + i, 35.80 + i * 0.005, 129.20 + i * 0.005));
        }
    }

    private Long attraction(String name, double lat, double lng) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr, source_content_id)
                values (?, '관광지', '47130', '설명', ?, ?, 14, '경주시', ?) returning id
                """, Long.class, name, lat, lng, UUID.randomUUID().toString());
        jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                id, "https://image.example/" + id);
        return id;
    }

    private Long trip() throws Exception {
        Long id = fixtures.createTrip(member.accessToken(), clock.today().plusDays(10), 1);
        jdbc.update("update app.trip_plans set region_id = '47130' where id = ?", id);
        return id;
    }

    private void restaurant(String sigCd, String name, double lat, double lng, String externalId) {
        Long id = jdbc.queryForObject("""
                insert into app.restaurants (region_id, name, category, lat, lng)
                values (?, ?, '한식', ?, ?) returning id
                """, Long.class, sigCd, name, lat, lng);
        jdbc.update("""
                insert into app.restaurant_sources (restaurant_id, provider, external_id, content_type_id, source_name, fetched_at)
                values (?, 'TOUR_API', ?, 39, '한국관광공사 TourAPI', now())
                """, id, externalId);
    }

    private MvcResult generate(Long tripId) throws Exception {
        return mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private MvcResult edit(Long tripId, String body) throws Exception {
        return mockMvc.perform(patch("/api/courses/{tripId}/schedule", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    @Nested
    @DisplayName("5-3 일정 편집")
    class Edit {
        @Test
        @DisplayName("토큰이 없으면 401, 참여자가 아니면 404 TRIP_NOT_FOUND, 코스가 없으면 404 COURSE_NOT_FOUND다")
        void authAndAccess() throws Exception {
            Long tripId = trip();
            String body = """
                    {"version":0,"operations":[{"op":"REMOVE","itemId":"a-1"}]}
                    """;
            MvcResult noCourse = edit(tripId, body);
            assertThat(noCourse.getResponse().getStatus()).isEqualTo(404);
            assertThat(code(noCourse)).isEqualTo("COURSE_NOT_FOUND");
            generate(tripId);
            mockMvc.perform(patch("/api/courses/{tripId}/schedule", tripId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(patch("/api/courses/{tripId}/schedule", tripId)
                            .header("Authorization", fixtures.onboardedMember().bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("operation이 비었거나 형식이 틀리면 400 COURSE_INVALID_OPERATION다")
        void invalidFormat() throws Exception {
            Long tripId = trip();
            generate(tripId);
            for (String body : List.of(
                    """
                    {"version":1,"operations":[]}""",
                    """
                    {"version":1,"operations":[{"op":"JUMP","itemId":"a-1"}]}""",
                    """
                    {"version":1,"operations":[{"op":"REMOVE","itemId":"x-1"}]}""",
                    """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"a-1","dayIndex":0}]}""")) {
                MvcResult result = edit(tripId, body);
                assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
                assertThat(code(result)).as(body).isEqualTo("COURSE_INVALID_OPERATION");
            }
        }

        @Test
        @DisplayName("없는 항목은 404 COURSE_ITEM_NOT_FOUND, 없는 관광지는 404 ATTRACTION_NOT_FOUND다")
        void missingItemAndAttraction() throws Exception {
            Long tripId = trip();
            generate(tripId);
            MvcResult item = edit(tripId, """
                    {"version":1,"operations":[{"op":"REMOVE","itemId":"a-999999"}]}
                    """);
            assertThat(item.getResponse().getStatus()).isEqualTo(404);
            assertThat(code(item)).isEqualTo("COURSE_ITEM_NOT_FOUND");
            MvcResult attraction = edit(tripId, """
                    {"version":1,"operations":[{"op":"ADD","dayIndex":0,"attractionId":999999}]}
                    """);
            assertThat(attraction.getResponse().getStatus()).isEqualTo(404);
            assertThat(code(attraction)).isEqualTo("ATTRACTION_NOT_FOUND");
        }

        @Test
        @DisplayName("여행이 끝났으면 409 TRIP_ENDED다")
        void endedTrip() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String item = read(first, "$.days[0].items[0].itemId");
            clock.advance(Duration.ofDays(12));
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":1}]}
                    """.formatted(item));
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(code(result)).isEqualTo("TRIP_ENDED");
        }

        @Test
        @DisplayName("다른 지역은 422 ATTRACTION_REGION_MISMATCH, 추천 불가는 422 ATTRACTION_NOT_RECOMMENDABLE, 코스에 있으면 409 ATTRACTION_ALREADY_IN_COURSE다")
        void attractionRules() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            Integer inCourse = read(first, "$.days[0].items[0].attractionId");
            jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('11110', '서울특별시', '종로구', 37.57, 126.98)");
            Long otherRegion = jdbc.queryForObject("""
                    insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr, source_content_id)
                    values ('경복궁', '관광지', '11110', '설명', 37.58, 126.97, 14, '종로구', ?) returning id
                    """, Long.class, UUID.randomUUID().toString());
            Long noImage = jdbc.queryForObject("""
                    insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr, source_content_id)
                    values ('사진 없는 곳', '관광지', '47130', '설명', 35.85, 129.22, 14, '경주시', ?) returning id
                    """, Long.class, UUID.randomUUID().toString());
            String add = """
                    {"version":1,"operations":[{"op":"ADD","dayIndex":0,"attractionId":%d}]}
                    """;

            MvcResult mismatch = edit(tripId, add.formatted(otherRegion));
            assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
            assertThat(code(mismatch)).isEqualTo("ATTRACTION_REGION_MISMATCH");
            MvcResult notRecommendable = edit(tripId, add.formatted(noImage));
            assertThat(notRecommendable.getResponse().getStatus()).isEqualTo(422);
            assertThat(code(notRecommendable)).isEqualTo("ATTRACTION_NOT_RECOMMENDABLE");
            MvcResult duplicate = edit(tripId, add.formatted(inCourse));
            assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
            assertThat(code(duplicate)).isEqualTo("ATTRACTION_ALREADY_IN_COURSE");
        }

        @Test
        @DisplayName("교체하면 itemId는 그대로이고 관광지만 바뀌며 MANUAL·reason 없음으로 표시한다")
        void replaceKeepsItemId() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String item = read(first, "$.days[0].items[0].itemId");
            Long replacement = attraction("교체 장소", 35.88, 129.28);
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[{"op":"REPLACE","itemId":"%s","attractionId":%d}]}
                    """.formatted(item, replacement));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(result, "$.days[0].items[0].itemId")).isEqualTo(item);
            assertThat((Integer) read(result, "$.days[0].items[0].attractionId")).isEqualTo(replacement.intValue());
            assertThat((String) read(result, "$.days[0].items[0].source")).isEqualTo("MANUAL");
            assertThat((Object) read(result, "$.days[0].items[0].reason")).isNull();
            assertThat((Integer) read(result, "$.updatedBy.userId")).isEqualTo(member.userId().intValue());
        }

        @Test
        @DisplayName("관광지는 다른 날 맨 앞으로 옮길 수 있고, 그날 첫 관광지라 이동시간이 없다")
        void moveAttractionToAnotherDay() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String item = read(first, "$.days[0].items[1].itemId");
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":1,"position":0}]}
                    """.formatted(item));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(result, "$.days[1].items[0].itemId")).isEqualTo(item);
            assertThat((Object) read(result, "$.days[1].items[0].travelFromPreviousMinutes")).isNull();
        }

        @Test
        @DisplayName("식사를 다른 날로 옮기거나 관광지로 교체하거나, 관광지에 식당 op를 쓰면 400 COURSE_INVALID_OPERATION다")
        void mealRules() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String attractionItem = read(first, "$.days[0].items[0].itemId");
            String meal = read(first, "$.days[0].items[2].itemId");
            Long extra = attraction("추가 장소", 35.89, 129.29);
            for (String operation : List.of(
                    """
                    {"op":"MOVE","itemId":"%s","dayIndex":1,"position":0}""".formatted(meal),
                    """
                    {"op":"REPLACE","itemId":"%s","attractionId":%d}""".formatted(meal, extra),
                    """
                    {"op":"CLEAR_RESTAURANT","itemId":"%s"}""".formatted(attractionItem))) {
                MvcResult result = edit(tripId, "{\"version\":1,\"operations\":[" + operation + "]}");
                assertThat(result.getResponse().getStatus()).as(operation).isEqualTo(400);
                assertThat(code(result)).as(operation).isEqualTo("COURSE_INVALID_OPERATION");
            }
        }

        @Test
        @DisplayName("식사는 같은 날 안에서 순서를 옮길 수 있고, 뒤 관광지의 이동시간은 식사를 건너뛴다")
        void moveMealWithinDay() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = read(first, "$.days[0].items[2].itemId");
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":0}]}
                    """.formatted(meal));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(result, "$.days[0].items[0].itemId")).isEqualTo(meal);
            assertThat((Object) read(result, "$.days[0].items[1].travelFromPreviousMinutes")).isNull();
        }

        @Test
        @DisplayName("한 날의 관광지를 모두 지워도 된다(하루 0곳 허용)")
        void emptyDayAllowed() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            List<String> dayTwo = read(first, "$.days[1].items[?(@.type == 'ATTRACTION')].itemId");
            assertThat(dayTwo).isNotEmpty();
            String operations = String.join(",", dayTwo.stream()
                    .map(id -> "{\"op\":\"REMOVE\",\"itemId\":\"" + id + "\"}").toList());
            MvcResult result = edit(tripId, "{\"version\":1,\"operations\":[" + operations + "]}");
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            List<String> types = read(result, "$.days[1].items[*].type");
            assertThat(types).containsOnly("MEAL").hasSize(2);
        }

        @Test
        @DisplayName("코스의 관광지가 추천 대상에서 빠져도 편집은 되고 경고만 붙는다")
        void noLongerRecommendableOnlyWarns() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String stale = read(first, "$.days[0].items[0].itemId");
            Integer staleAttraction = read(first, "$.days[0].items[0].attractionId");
            String other = read(first, "$.days[0].items[1].itemId");
            jdbc.update("update app.attraction_images set validation_status = 'INVALID' where attraction_id = ?",
                    staleAttraction);
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":0}]}
                    """.formatted(other));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            List<String> warned = read(result, "$.warnings[?(@.code == 'ATTRACTION_NO_LONGER_RECOMMENDABLE')].itemId");
            assertThat(warned).containsExactly(stale);
        }

        @Test
        @DisplayName("위조한 선택 토큰은 422 COURSE_RESTAURANT_SELECTION_INVALID다")
        void invalidSelectionToken() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = read(first, "$.days[0].items[2].itemId");
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[{"op":"SET_RESTAURANT","itemId":"%s","selectionToken":"forged.token"}]}
                    """.formatted(meal));
            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            assertThat(code(result)).isEqualTo("COURSE_RESTAURANT_SELECTION_INVALID");
        }

        @Test
        @DisplayName("같은 버전으로 동시에 편집하면 한 요청만 성공한다")
        void concurrentEdits() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String item = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[0].itemId");
            String body = """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":1}]}
                    """.formatted(item);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    start.await();
                    int status = edit(tripId, body).getResponse().getStatus();
                    if (status == 200) ok.incrementAndGet();
                    if (status == 409) conflict.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(ok.get()).isEqualTo(1);
            assertThat(conflict.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("관광지를 추가·이동·삭제하면 순서와 이동시간을 다시 계산하고 버전을 올린다")
        void editsSchedule() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String oldItem = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[0].itemId");
            Long extra = attraction("추가 장소", 35.89, 129.29);
            MvcResult edited = edit(tripId, """
                    {"version":1,"operations":[
                      {"op":"REMOVE","itemId":"%s"},
                      {"op":"ADD","dayIndex":0,"position":0,"attractionId":%d}
                    ]}
                    """.formatted(oldItem, extra));
            assertThat(edited.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) JsonPath.read(edited.getResponse().getContentAsString(), "$.version")).isEqualTo(2);
            assertThat((Integer) JsonPath.read(edited.getResponse().getContentAsString(),
                    "$.days[0].items[0].attractionId")).isEqualTo(extra.intValue());
            assertThat((String) JsonPath.read(edited.getResponse().getContentAsString(),
                    "$.days[0].items[0].source")).isEqualTo("MANUAL");
            assertThat(jdbc.queryForObject("select count(*) from app.course_items where trip_plan_id = ? and attraction_id = ?",
                    Integer.class, tripId, extra)).isEqualTo(1);
        }

        @Test
        @DisplayName("뒤 operation이 실패하면 앞의 변경도 저장되지 않는다")
        void rollbackOnInvalidOperation() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[2].itemId");
            Long extra = attraction("추가 장소", 35.89, 129.29);
            MvcResult result = edit(tripId, """
                    {"version":1,"operations":[
                      {"op":"ADD","dayIndex":0,"attractionId":%d},
                      {"op":"REMOVE","itemId":"%s"}
                    ]}
                    """.formatted(extra, meal));
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("COURSE_INVALID_OPERATION");
            assertThat(jdbc.queryForObject("select count(*) from app.course_items where trip_plan_id = ? and attraction_id = ?",
                    Integer.class, tripId, extra)).isZero();
            mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", member.bearer()))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("같은 버전으로 다시 편집하면 409 TRIP_VERSION_CONFLICT다")
        void staleVersion() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String item = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[0].itemId");
            String body = """
                    {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":1}]}
                    """.formatted(item);
            assertThat(edit(tripId, body).getResponse().getStatus()).isEqualTo(200);
            MvcResult stale = edit(tripId, body);
            assertThat(stale.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(stale.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("TRIP_VERSION_CONFLICT");
        }
    }

    @Nested
    @DisplayName("5-4 대체 후보")
    class Alternatives {
        @Test
        @DisplayName("이름 검색은 코스 밖의 같은 지역 추천 가능 관광지만 유형별로 반환한다")
        void search() throws Exception {
            Long tripId = trip();
            generate(tripId);
            attraction("추가 장소", 35.89, 129.29);
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("q", "추가 장소"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groups[1].category").value("HISTORY_CULTURE"))
                    .andExpect(jsonPath("$.groups[1].items[0].name").value("추가 장소"));
        }

        @Test
        @DisplayName("교체 후보의 이유는 같은 유형·동선 같은 실제 근거로 최대 2개를 잇는다")
        void replacementReasons() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String second = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[1].itemId");

            MvcResult result = mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("itemId", second))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groups[1].category").value("HISTORY_CULTURE"))
                    .andReturn();
            List<String> reasons = JsonPath.read(result.getResponse().getContentAsString(), "$.groups[1].items[*].reason");
            List<Integer> minutes = JsonPath.read(result.getResponse().getContentAsString(),
                    "$.groups[1].items[*].travelFromPreviousMinutes");

            assertThat(reasons).isNotEmpty();
            for (int i = 0; i < reasons.size(); i++) {
                assertThat(reasons.get(i)).isEqualTo(
                        "바꾸려는 곳과 같은 역사·문화 장소예요 · 앞 장소에서 약 " + minutes.get(i) + "분이에요");
            }
        }

        @Test
        @DisplayName("추가 후보(itemId 없음)는 근거가 없으면 이유가 없다")
        void additionWithoutEvidence_hasNoReason() throws Exception {
            Long tripId = trip();
            generate(tripId);

            MvcResult result = mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isOk())
                    .andReturn();
            List<Object> reasons = JsonPath.read(result.getResponse().getContentAsString(), "$.groups[*].items[*].reason");

            assertThat(reasons).containsOnlyNulls();
        }

        @Test
        @DisplayName("잘못된 검색어와 식사 항목은 각각 400 오류다")
        void invalidQueryAndItem() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[2].itemId");
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("q", "   "))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("itemId", meal))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COURSE_INVALID_OPERATION"));
        }
    }

    @Nested
    @DisplayName("5-4 대체 후보 오류")
    class AlternativesErrors {
        @Test
        @DisplayName("토큰이 없으면 401, 참여자가 아니면 404 TRIP_NOT_FOUND, 없는 항목은 404 COURSE_ITEM_NOT_FOUND다")
        void authAndAccess() throws Exception {
            Long tripId = trip();
            generate(tripId);
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", fixtures.onboardedMember().bearer()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("itemId", "a-999999"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COURSE_ITEM_NOT_FOUND"));
        }

        @Test
        @DisplayName("잘못된 category·limit은 400 COMMON_INVALID_REQUEST, 끝난 여행은 409 TRIP_ENDED다")
        void invalidParamsAndEnded() throws Exception {
            Long tripId = trip();
            generate(tripId);
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("category", "FOOD"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()).param("limit", "31"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
            clock.advance(Duration.ofDays(12));
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIP_ENDED"));
        }

        @Test
        @DisplayName("category를 주면 그 유형 그룹만 limit 개수까지 준다")
        void categoryAndLimit() throws Exception {
            Long tripId = trip();
            generate(tripId);
            attraction("추가 장소 2", 35.90, 129.30);
            attraction("추가 장소 3", 35.91, 129.31);
            mockMvc.perform(get("/api/courses/{tripId}/alternatives", tripId)
                            .header("Authorization", member.bearer())
                            .param("category", "HISTORY_CULTURE").param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groups.length()").value(1))
                    .andExpect(jsonPath("$.groups[0].category").value("HISTORY_CULTURE"))
                    .andExpect(jsonPath("$.groups[0].items.length()").value(1));
        }
    }

    @Nested
    @DisplayName("5-5 식당 추천·선택")
    class Restaurants {
        @Test
        @DisplayName("반경이 20000미터보다 크면 400 COMMON_INVALID_REQUEST다")
        void invalidRadius() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[2].itemId");
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", member.bearer()).param("itemId", meal).param("radius", "20001"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }

        @Test
        @DisplayName("토큰이 없으면 401, 참여자가 아니면 404 TRIP_NOT_FOUND, 관광지 항목이면 400 COURSE_INVALID_OPERATION다")
        void recommendationErrors() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = read(first, "$.days[0].items[2].itemId");
            String attractionItem = read(first, "$.days[0].items[0].itemId");
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId).param("itemId", meal))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", fixtures.onboardedMember().bearer()).param("itemId", meal))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", member.bearer()).param("itemId", attractionItem))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COURSE_INVALID_OPERATION"));
        }

        @Test
        @DisplayName("같은 지역·반경 안의 TourAPI 식당만 가까운 순으로 주고, 공공 지정 식당 섹션과 지역 음식 테마는 비어 있다")
        void recommendationFilterAndOrder() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = read(first, "$.days[0].items[2].itemId");
            Double lat = read(first, "$.days[0].items[1].lat");
            Double lng = read(first, "$.days[0].items[1].lng");
            jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47290', '경상북도', '경산시', 35.82, 128.74)");
            restaurant("47130", "먼 식당", lat + 0.008, lng, "far");
            restaurant("47130", "가까운 식당", lat + 0.001, lng, "near");
            restaurant("47130", "반경 밖 식당", lat + 0.05, lng, "outside");
            restaurant("47290", "다른 지역 식당", lat, lng, "other-region");
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", member.bearer()).param("itemId", meal).param("radius", "2000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.origin.type").value("PREVIOUS_ATTRACTION"))
                    .andExpect(jsonPath("$.regionFoodThemes.length()").value(0))
                    .andExpect(jsonPath("$.sections[0].source").value("TOUR_API"))
                    .andExpect(jsonPath("$.sections[0].items.length()").value(2))
                    .andExpect(jsonPath("$.sections[0].items[0].name").value("가까운 식당"))
                    .andExpect(jsonPath("$.sections[0].items[1].name").value("먼 식당"))
                    .andExpect(jsonPath("$.sections[1].items.length()").value(0))
                    .andExpect(jsonPath("$.sections[2].items.length()").value(0))
                    .andExpect(jsonPath("$.sections[3].items.length()").value(0));
        }

        @Test
        @DisplayName("끝난 여행이어도 코스가 없으면 409가 아니라 404 COURSE_NOT_FOUND다")
        void notFoundBeforeEnded() throws Exception {
            Long tripId = trip();
            clock.advance(Duration.ofDays(12));
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", member.bearer()).param("itemId", "m-1"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
        }

        @Test
        @DisplayName("식당 데이터가 없으면 모든 섹션이 빈 배열이다")
        void emptySections() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = read(first, "$.days[0].items[2].itemId");
            mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", member.bearer()).param("itemId", meal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sections.length()").value(4))
                    .andExpect(jsonPath("$.sections[0].items.length()").value(0));
        }

        @Test
        @DisplayName("코스가 있는 여행의 이동수단을 바꾸면 이동시간을 다시 계산한다")
        void contextTransportRecalculatesCourse() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            Integer before = JsonPath.read(first.getResponse().getContentAsString(),
                    "$.days[0].items[1].travelFromPreviousMinutes");
            mockMvc.perform(patch("/api/trips/{tripId}/context", tripId)
                            .header("Authorization", member.bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1,\"transport\":\"CAR\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.hasCourse").value(true));
            MvcResult after = mockMvc.perform(get("/api/courses/{tripId}", tripId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isOk()).andReturn();
            Integer recalculated = JsonPath.read(after.getResponse().getContentAsString(),
                    "$.days[0].items[1].travelFromPreviousMinutes");
            assertThat(recalculated).isLessThan(before);
        }

        @Test
        @DisplayName("같은 지역의 가까운 TourAPI 식당을 추천하고 토큰으로 선택·해제한다")
        void recommendSelectClear() throws Exception {
            Long tripId = trip();
            MvcResult first = generate(tripId);
            String meal = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[2].itemId");
            Double lat = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[1].lat");
            Double lng = JsonPath.read(first.getResponse().getContentAsString(), "$.days[0].items[1].lng");
            Long restaurantId = jdbc.queryForObject("""
                    insert into app.restaurants (region_id, name, category, lat, lng)
                    values ('47130', '식당 하나', '한식', ?, ?) returning id
                    """, Long.class, lat, lng);
            jdbc.update("""
                    insert into app.restaurant_sources (restaurant_id, provider, external_id, content_type_id, source_name, fetched_at)
                    values (?, 'TOUR_API', 'restaurant-1', 39, '한국관광공사 TourAPI', now())
                    """, restaurantId);
            MvcResult recommended = mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                            .header("Authorization", member.bearer()).param("itemId", meal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sections[0].items[0].name").value("식당 하나"))
                    .andExpect(jsonPath("$.sections[1].items.length()").value(0)).andReturn();
            String token = JsonPath.read(recommended.getResponse().getContentAsString(),
                    "$.sections[0].items[0].selectionToken");
            MvcResult selected = edit(tripId, """
                    {"version":1,"operations":[{"op":"SET_RESTAURANT","itemId":"%s","selectionToken":"%s"}]}
                    """.formatted(meal, token));
            assertThat(selected.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(selected.getResponse().getContentAsString(),
                    "$.days[0].items[2].restaurant.name")).isEqualTo("식당 하나");
            MvcResult cleared = edit(tripId, """
                    {"version":2,"operations":[{"op":"CLEAR_RESTAURANT","itemId":"%s"}]}
                    """.formatted(meal));
            assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from app.course_meal_restaurants", Integer.class)).isZero();
        }
    }
}
