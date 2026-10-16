package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import com.yeso.backend.trip.infrastructure.FakeKakaoMapRouteClient;
import com.yeso.backend.trip.infrastructure.FakeKakaoMobilityClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 5-7 하루 코스의 지도 경로(docs/api/trip.md 5-7). 카카오모빌리티는 fake다. */
class CourseRoutesIntegrationTest extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    private Member member;

    @BeforeEach
    void setUp() throws Exception {
        member = fixtures.onboardedMember("경로");
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47130', '경상북도', '경주시', 35.856, 129.225)");
        for (int i = 0; i < 13; i++) {
            Long id = jdbc.queryForObject("""
                    insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr, source_content_id)
                    values (?, '관광지', '47130', '설명', ?, ?, 14, '경주시', ?) returning id
                    """, Long.class, "관광지" + i, 35.80 + i * 0.005, 129.20 + i * 0.005, UUID.randomUUID().toString());
            jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                    id, "https://image.example/" + id);
        }
    }

    private Long trip(String transport) throws Exception {
        MvcResult created = fixtures.createTripResult(member.accessToken(), clock.today().plusDays(10), 1, transport);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        Long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        jdbc.update("update app.trip_plans set region_id = '47130' where id = ?", id);
        return id;
    }

    private void restaurantsNearEveryAttraction() {
        for (int i = 0; i < 13; i++) {
            Long id = jdbc.queryForObject("""
                    insert into app.restaurants (region_id, name, category, lat, lng)
                    values ('47130', ?, '한식', ?, ?) returning id
                    """, Long.class, "식당" + i, 35.8003 + i * 0.005, 129.2003 + i * 0.005);
            jdbc.update("""
                    insert into app.restaurant_sources (restaurant_id, provider, external_id, content_type_id, source_name, fetched_at)
                    values (?, 'TOUR_API', ?, 39, '한국관광공사 TourAPI', now())
                    """, id, "r-" + i);
        }
    }

    private MvcResult generate(Long tripId) throws Exception {
        return mockMvc.perform(post("/api/courses/{tripId}/generate", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private MvcResult routes(Long tripId, int dayIndex) throws Exception {
        return mockMvc.perform(get("/api/courses/{tripId}/routes", tripId)
                        .header("Authorization", member.bearer()).param("dayIndex", String.valueOf(dayIndex)))
                .andExpect(status().isOk()).andReturn();
    }

    private static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    @Test
    @DisplayName("자동차 여행은 고른 식당까지 이어 도로 경로·거리·시간을 내려주고, 하루를 한 번에 길찾기한다")
    void carDayRoutes() throws Exception {
        restaurantsNearEveryAttraction();
        Long tripId = trip("CAR");
        MvcResult course = generate(tripId);
        List<String> itemIds = read(course, "$.days[0].items[*].itemId");

        MvcResult result = routes(tripId, 0);
        assertThat((String) read(result, "$.transport")).isEqualTo("CAR");
        assertThat((Integer) read(result, "$.dayIndex")).isZero();
        List<String> from = read(result, "$.legs[*].fromItemId");
        List<String> to = read(result, "$.legs[*].toItemId");
        // 식당이 모두 배정됐으므로 모든 항목이 지점이다: 항목 수 - 1개 구간이 순서대로
        assertThat(from).containsExactlyElementsOf(itemIds.subList(0, itemIds.size() - 1));
        assertThat(to).containsExactlyElementsOf(itemIds.subList(1, itemIds.size()));
        assertThat((List<String>) read(result, "$.legs[*].source")).containsOnly("ROAD");
        assertThat((Integer) read(result, "$.legs[0].distanceMeters")).isEqualTo(FakeKakaoMobilityClient.DISTANCE_METERS);
        assertThat((Integer) read(result, "$.legs[0].durationMinutes")).isEqualTo(5);
        assertThat((List<?>) read(result, "$.legs[0].path")).hasSize(3);
        assertThat((Double) read(result, "$.legs[0].path[0][0]"))
                .isEqualTo((Double) read(course, "$.days[0].items[0].lat"));
        assertThat(fakeKakaoMobilityClient.requests()).hasSize(1);
        assertThat(fakeKakaoMobilityClient.requests().get(0)).hasSize(itemIds.size());
    }

    @Test
    @DisplayName("같은 코스를 다시 열면 기억해 둔 구간을 써서 길찾기를 다시 부르지 않는다")
    void secondOpenUsesCache() throws Exception {
        Long tripId = trip("CAR");
        generate(tripId);
        routes(tripId, 0);
        int calls = fakeKakaoMobilityClient.requests().size();
        MvcResult again = routes(tripId, 0);
        assertThat(fakeKakaoMobilityClient.requests()).hasSize(calls);
        assertThat((List<String>) read(again, "$.legs[*].source")).containsOnly("ROAD");
    }

    @Test
    @DisplayName("장소를 바꾸면 바뀐 장소 앞뒤 구간만 다시 길찾기한다")
    void editRoutesOnlyChangedLegs() throws Exception {
        Long tripId = trip("CAR");
        MvcResult course = generate(tripId);
        routes(tripId, 0);
        int calls = fakeKakaoMobilityClient.requests().size();

        String second = read(course, "$.days[0].items[1].itemId");
        Long replacement = jdbc.queryForObject("""
                select id from app.attractions
                where id not in (select attraction_id from app.course_items where attraction_id is not null)
                order by id limit 1
                """, Long.class);
        mockMvc.perform(patch("/api/courses/{tripId}/schedule", tripId)
                        .header("Authorization", member.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"operations":[{"op":"REPLACE","itemId":"%s","attractionId":%d}]}
                                """.formatted(second, replacement)))
                .andExpect(status().isOk());
        MvcResult after = routes(tripId, 0);

        List<?> newRequests = fakeKakaoMobilityClient.requests().subList(calls, fakeKakaoMobilityClient.requests().size());
        assertThat(newRequests).hasSize(1);
        // 앞 장소 → 바뀐 장소 → (식당 미정 점심을 건너뛴) 다음 장소: 3개 지점, 구간 2개
        assertThat((List<?>) newRequests.get(0)).hasSize(3);
        assertThat((List<String>) read(after, "$.legs[*].source")).containsOnly("ROAD");
    }

    @Test
    @DisplayName("식당이 미정인 식사는 건너뛰고 앞뒤 장소를 바로 잇는다")
    void emptyMealIsSkipped() throws Exception {
        Long tripId = trip("CAR");
        MvcResult course = generate(tripId);
        String beforeLunch = read(course, "$.days[0].items[1].itemId");
        String afterLunch = read(course, "$.days[0].items[3].itemId");
        MvcResult result = routes(tripId, 0);
        assertThat((List<String>) read(result, "$.legs[*].toItemId")).noneMatch(id -> id.startsWith("m-"));
        assertThat((List<String>) read(result, "$.legs[?(@.toItemId == '%s')].fromItemId".formatted(afterLunch)))
                .containsExactly(beforeLunch);
    }

    @Test
    @DisplayName("도보 여행은 카카오맵 도보 경로로 구간마다 경로·거리·시간을 내려주고 자동차 길찾기는 부르지 않는다")
    void walkUsesKakaoMapRoute() throws Exception {
        Long tripId = trip("WALK");
        generate(tripId);
        MvcResult result = routes(tripId, 0);
        List<String> sources = read(result, "$.legs[*].source");
        assertThat(sources).isNotEmpty().containsOnly("WALK");
        assertThat((Integer) read(result, "$.legs[0].durationMinutes")).isEqualTo(FakeKakaoMapRouteClient.WALK_SECONDS / 60);
        assertThat((Integer) read(result, "$.legs[0].distanceMeters")).isEqualTo(FakeKakaoMapRouteClient.WALK_METERS);
        assertThat((List<?>) read(result, "$.legs[0].path")).hasSize(3);
        assertThat((Object) read(result, "$.legs[0].transfers")).isNull();
        assertThat(fakeKakaoMobilityClient.requests()).isEmpty();
        assertThat(fakeKakaoMapRouteClient.calls()).hasSize(sources.size()).containsOnly("WALK");

        // 다시 열면 기억해 둔 구간을 쓴다
        routes(tripId, 0);
        assertThat(fakeKakaoMapRouteClient.calls()).hasSize(sources.size());
    }

    @Test
    @DisplayName("대중교통은 환승·요금을 함께 주고, transport로 다른 수단의 경로를 볼 수 있다")
    void transitAndOverride() throws Exception {
        Long tripId = trip("CAR");
        generate(tripId);
        MvcResult transit = mockMvc.perform(get("/api/courses/{tripId}/routes", tripId).header("Authorization", member.bearer())
                        .param("dayIndex", "0").param("transport", "PUBLIC_TRANSIT"))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) read(transit, "$.transport")).isEqualTo("PUBLIC_TRANSIT");
        assertThat((List<String>) read(transit, "$.legs[*].source")).containsOnly("TRANSIT");
        assertThat((Integer) read(transit, "$.legs[0].transfers")).isEqualTo(1);
        assertThat((Integer) read(transit, "$.legs[0].fare")).isEqualTo(FakeKakaoMapRouteClient.TRANSIT_FARE);
        assertThat(fakeKakaoMobilityClient.requests()).isEmpty();

        // 지도에서 받은 실제 경로 시간이 코스 카드의 대중교통 시간이 된다
        MvcResult course = mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", member.bearer())).andReturn();
        List<String> transitSources = read(course, "$.days[0].items[?(@.travelFromPrevious != null)].travelFromPrevious.transitSource");
        assertThat(transitSources).contains("ROUTE");
        List<Integer> transitMinutes = read(course, "$.days[0].items[?(@.travelFromPrevious.transitSource == 'ROUTE')].travelFromPrevious.transitMinutes");
        assertThat(transitMinutes).containsOnly(FakeKakaoMapRouteClient.TRANSIT_SECONDS / 60);
        List<Integer> fares = read(course, "$.days[0].items[?(@.travelFromPrevious.transitSource == 'ROUTE')].travelFromPrevious.transitFare");
        assertThat(fares).containsOnly(FakeKakaoMapRouteClient.TRANSIT_FARE);

        mockMvc.perform(get("/api/courses/{tripId}/routes", tripId).header("Authorization", member.bearer())
                        .param("dayIndex", "0").param("transport", "BIKE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("도보·대중교통 경로를 못 받으면 직선 구간이고 요청은 성공한다")
    void mapRouteFailureFallsBackToStraight() throws Exception {
        fakeKakaoMapRouteClient.setFail(true);
        Long tripId = trip("WALK");
        MvcResult course = generate(tripId);
        MvcResult result = routes(tripId, 0);
        assertThat((List<String>) read(result, "$.legs[*].source")).containsOnly("STRAIGHT");
        assertThat((Integer) read(result, "$.legs[0].durationMinutes"))
                .isEqualTo((Integer) read(course, "$.days[0].items[1].travelFromPreviousMinutes"));
    }

    @Test
    @DisplayName("길찾기가 실패하면 직선 구간으로 내려주고 요청은 성공한다")
    void failureFallsBackToStraight() throws Exception {
        fakeKakaoMobilityClient.setMode(FakeKakaoMobilityClient.Mode.FAIL);
        Long tripId = trip("CAR");
        MvcResult course = generate(tripId);
        MvcResult result = routes(tripId, 0);
        assertThat((List<String>) read(result, "$.legs[*].source")).containsOnly("STRAIGHT");
        // 직선 구간의 시간은 코스 응답의 추정 이동시간과 같다
        assertThat((Integer) read(result, "$.legs[0].durationMinutes"))
                .isEqualTo((Integer) read(course, "$.days[0].items[1].travelFromPreviousMinutes"));
        assertThat((Integer) read(result, "$.legs[0].distanceMeters"))
                .isEqualTo((Integer) read(course, "$.days[0].items[1].travelFromPreviousMeters"));
    }

    @Test
    @DisplayName("날짜 범위 밖이거나 빠졌으면 400, 코스가 없으면 404 COURSE_NOT_FOUND, 참여자가 아니면 404 TRIP_NOT_FOUND다")
    void errors() throws Exception {
        Long tripId = trip("CAR");
        mockMvc.perform(get("/api/courses/{tripId}/routes", tripId).header("Authorization", member.bearer())
                        .param("dayIndex", "0"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
        generate(tripId);
        for (String dayIndex : List.of("-1", "2")) {
            mockMvc.perform(get("/api/courses/{tripId}/routes", tripId).header("Authorization", member.bearer())
                            .param("dayIndex", dayIndex))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }
        mockMvc.perform(get("/api/courses/{tripId}/routes", tripId).header("Authorization", member.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        mockMvc.perform(get("/api/courses/{tripId}/routes", tripId)
                        .header("Authorization", fixtures.onboardedMember().bearer()).param("dayIndex", "0"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }
}
