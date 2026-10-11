package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #101 자동차 이동값을 카카오모빌리티 도로 값으로 반영(docs/api/trip.md 5장 travelSource). 카카오는 fake다. */
class CourseRouteIntegrationTest extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    private Member member;

    @BeforeEach
    void setUp() throws Exception {
        member = fixtures.onboardedMember("길찾기");
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47130', '경상북도', '경주시', 35.856, 129.225)");
        for (int i = 0; i < 12; i++) {
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
        for (int i = 0; i < 12; i++) {
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

    private MvcResult edit(Long tripId, String body) throws Exception {
        return mockMvc.perform(patch("/api/courses/{tripId}/schedule", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult course(Long tripId) throws Exception {
        return mockMvc.perform(get("/api/courses/{tripId}", tripId).header("Authorization", member.bearer()))
                .andExpect(status().isOk()).andReturn();
    }

    private static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    @Test
    @DisplayName("자동차 여행은 생성 응답과 저장값이 도로 값이고, 식당까지 잇고, 추정 경고가 없다")
    void carGenerateUsesRoad() throws Exception {
        restaurantsNearEveryAttraction();
        Long tripId = trip("CAR");
        MvcResult result = generate(tripId);
        assertThat((String) read(result, "$.days[0].items[1].travelSource")).isEqualTo("ROAD");
        assertThat((Integer) read(result, "$.days[0].items[1].travelFromPreviousMinutes")).isEqualTo(5);
        assertThat((Integer) read(result, "$.days[0].items[1].travelFromPreviousMeters"))
                .isEqualTo(FakeKakaoMobilityClient.DISTANCE_METERS);
        assertThat((String) read(result, "$.days[0].items[2].type")).isEqualTo("MEAL");
        assertThat((String) read(result, "$.days[0].items[2].travelSource")).isEqualTo("ROAD");
        assertThat((String) read(result, "$.days[0].items[3].travelSource")).isEqualTo("ROAD");
        assertThat((List<String>) read(result, "$.warnings[*].code")).doesNotContain("ROUTE_TIME_ESTIMATED");
        assertThat(fakeKakaoMobilityClient.requests()).isNotEmpty()
                .allSatisfy(points -> assertThat(points).hasSizeBetween(2, 7));
        assertThat((String) read(course(tripId), "$.days[0].items[1].travelSource")).isEqualTo("ROAD");
    }

    @Test
    @DisplayName("식당이 없는 식사 슬롯 앞뒤 구간은 비어 있고 나머지는 도로 값이다")
    void emptyMealBreaksChain() throws Exception {
        Long tripId = trip("CAR");
        MvcResult result = generate(tripId);
        assertThat((Object) read(result, "$.days[0].items[2].restaurant")).isNull();
        assertThat((Object) read(result, "$.days[0].items[2].travelSource")).isNull();
        assertThat((Object) read(result, "$.days[0].items[3].travelFromPreviousMinutes")).isNull();
        assertThat((String) read(result, "$.days[0].items[1].travelSource")).isEqualTo("ROAD");
    }

    @Test
    @DisplayName("길찾기가 실패해도 생성은 성공하고 추정값과 경고로 응답한다")
    void failureFallsBackToEstimate() throws Exception {
        fakeKakaoMobilityClient.setMode(FakeKakaoMobilityClient.Mode.FAIL);
        Long tripId = trip("CAR");
        MvcResult result = generate(tripId);
        assertThat((String) read(result, "$.days[0].items[1].travelSource")).isEqualTo("ESTIMATE");
        assertThat((Object) read(result, "$.days[0].items[1].travelFromPreviousMeters")).isNull();
        assertThat((List<String>) read(result, "$.warnings[*].code")).contains("ROUTE_TIME_ESTIMATED");
    }

    @Test
    @DisplayName("도보 여행은 길찾기를 부르지 않는다")
    void walkDoesNotCallKakao() throws Exception {
        Long tripId = trip("WALK");
        MvcResult result = generate(tripId);
        assertThat((String) read(result, "$.days[0].items[1].travelSource")).isEqualTo("ESTIMATE");
        assertThat(fakeKakaoMobilityClient.requests()).isEmpty();
    }

    @Test
    @DisplayName("식당을 고르면 그 식사와 다음 관광지의 구간이 도로 값으로 채워진다")
    void setRestaurantFillsMealLegs() throws Exception {
        Long tripId = trip("CAR");
        MvcResult first = generate(tripId);
        String meal = read(first, "$.days[0].items[2].itemId");
        restaurantsNearEveryAttraction();
        MvcResult recommended = mockMvc.perform(get("/api/courses/{tripId}/restaurants/recommendations", tripId)
                        .header("Authorization", member.bearer()).param("itemId", meal))
                .andExpect(status().isOk()).andReturn();
        String token = read(recommended, "$.sections[0].items[0].selectionToken");
        MvcResult selected = edit(tripId, """
                {"version":1,"operations":[{"op":"SET_RESTAURANT","itemId":"%s","selectionToken":"%s"}]}
                """.formatted(meal, token));
        assertThat((String) read(selected, "$.days[0].items[2].travelSource")).isEqualTo("ROAD");
        assertThat((String) read(selected, "$.days[0].items[3].travelSource")).isEqualTo("ROAD");
        assertThat((Integer) read(selected, "$.version")).isEqualTo(2);
    }

    @Test
    @DisplayName("길찾기 사이에 다른 편집이 끼면 낡은 도로 값으로 덮어쓰지 않는다")
    void concurrentEditSkipsStaleWrite() throws Exception {
        Long tripId = trip("CAR");
        MvcResult first = generate(tripId);
        String a0 = read(first, "$.days[0].items[0].itemId");
        String a1 = read(first, "$.days[0].items[1].itemId");
        fakeKakaoMobilityClient.setBeforeRespond(() -> {
            try {
                edit(tripId, """
                        {"version":2,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":0}]}
                        """.formatted(a1));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        MvcResult stale = edit(tripId, """
                {"version":1,"operations":[{"op":"MOVE","itemId":"%s","dayIndex":0,"position":1}]}
                """.formatted(a0));
        assertThat((String) read(stale, "$.days[0].items[1].travelSource")).isEqualTo("ESTIMATE");
        MvcResult latest = course(tripId);
        assertThat((Integer) read(latest, "$.version")).isEqualTo(3);
        assertThat((String) read(latest, "$.days[0].items[0].itemId")).isEqualTo(a1);
        assertThat((String) read(latest, "$.days[0].items[1].travelSource")).isEqualTo("ROAD");
    }

    @Test
    @DisplayName("이동수단을 자동차로 바꾸면 도로 값, 다시 도보로 바꾸면 추정값이고 호출하지 않는다")
    void transportChange() throws Exception {
        Long tripId = trip("WALK");
        generate(tripId);
        mockMvc.perform(patch("/api/trips/{tripId}/context", tripId).header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"transport\":\"CAR\"}"))
                .andExpect(status().isOk());
        assertThat((String) read(course(tripId), "$.days[0].items[1].travelSource")).isEqualTo("ROAD");
        int calls = fakeKakaoMobilityClient.requests().size();
        mockMvc.perform(patch("/api/trips/{tripId}/context", tripId).header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2,\"transport\":\"WALK\"}"))
                .andExpect(status().isOk());
        assertThat((String) read(course(tripId), "$.days[0].items[1].travelSource")).isEqualTo("ESTIMATE");
        assertThat(fakeKakaoMobilityClient.requests()).hasSize(calls);
    }

    @Test
    @DisplayName("이동수단이 그대로인 context 수정은 이미 도로 값이면 다시 부르지 않는다")
    void contextWithoutTransportChangeDoesNotCall() throws Exception {
        Long tripId = trip("CAR");
        generate(tripId);
        int calls = fakeKakaoMobilityClient.requests().size();
        mockMvc.perform(patch("/api/trips/{tripId}/context", tripId).header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"originLat\":35.85,\"originLng\":129.21}"))
                .andExpect(status().isOk());
        assertThat(fakeKakaoMobilityClient.requests()).hasSize(calls);
    }
}
