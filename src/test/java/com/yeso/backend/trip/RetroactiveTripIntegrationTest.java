package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 지난 여행 기록하기(docs/api/trip.md 3장 사후 기록 여행, 6-1). */
class RetroactiveTripIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private LocalDate daysAgo(int days) {
        return clock.today().minusDays(days);
    }

    private MvcResult createTrip(Member member, LocalDate startDate, int nights) throws Exception {
        return fixtures.createTripResult(member.accessToken(), startDate, nights, "WALK");
    }

    private Long tripId(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private MvcResult changeTransport(Member member, Long tripId, int version) throws Exception {
        return mockMvc.perform(patch("/api/trips/{tripId}/context", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transport\":\"CAR\",\"version\":" + version + "}"))
                .andReturn();
    }

    private MvcResult createDiary(Member member, Long tripId) throws Exception {
        return mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
    }

    @Test
    @DisplayName("종료일이 오늘 이전인 과거 날짜로 만들면 201이고 retroactive가 true다")
    void pastTripIsRetroactive() throws Exception {
        Member member = fixtures.onboardedMember();

        MvcResult created = createTrip(member, daysAgo(10), 2);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(created.getResponse().getContentAsString(), "$.retroactive")).isTrue();
    }

    @Test
    @DisplayName("종료일이 오늘이거나 이후면 retroactive가 false다")
    void currentAndFutureTripsAreNotRetroactive() throws Exception {
        Member member = fixtures.onboardedMember();

        MvcResult ongoing = createTrip(member, daysAgo(1), 1); // 종료일이 오늘
        MvcResult future = createTrip(member, clock.today().plusDays(10), 1);

        assertThat(JsonPath.<Boolean>read(ongoing.getResponse().getContentAsString(), "$.retroactive")).isFalse();
        assertThat(JsonPath.<Boolean>read(future.getResponse().getContentAsString(), "$.retroactive")).isFalse();
    }

    @Test
    @DisplayName("시작일이 없으면 400 TRIP_INVALID_START_DATE이고 6박 초과는 과거 날짜에서도 막는다")
    void stillValidatesStartDateAndNights() throws Exception {
        Member member = fixtures.onboardedMember();

        mockMvc.perform(post("/api/trips")
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nights\":1,\"transport\":\"WALK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TRIP_INVALID_START_DATE"));
        assertThat(createTrip(member, daysAgo(30), 7).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("날짜 중복 미리 확인(3-2)도 과거 날짜를 받는다")
    void checkContextAcceptsPastDate() throws Exception {
        Member member = fixtures.onboardedMember();

        mockMvc.perform(post("/api/trips/context/check")
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"" + daysAgo(10) + "\",\"nights\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("날짜 중복 차단은 과거 여행에도 동작한다")
    void overlapBlockedForPastTrips() throws Exception {
        Member member = fixtures.onboardedMember();
        tripId(createTrip(member, daysAgo(10), 2));

        MvcResult overlapping = createTrip(member, daysAgo(9), 0);

        assertThat(overlapping.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(overlapping.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("TRIP_DATE_OVERLAP");
    }

    @Test
    @DisplayName("사후 기록 여행은 여행기를 만들기 전까지 이동수단을 고칠 수 있다")
    void retroactiveTripIsEditableBeforeDiary() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, daysAgo(10), 1));

        assertThat(changeTransport(member, tripId, 0).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("여행기를 만들면 사후 기록 여행도 409 TRIP_ENDED로 잠기고, 여행기를 지우면 다시 고칠 수 있다")
    void diaryLocksRetroactiveTrip() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, daysAgo(10), 1));
        MvcResult diary = createDiary(member, tripId);
        assertThat(diary.getResponse().getStatus()).isEqualTo(201);

        MvcResult locked = changeTransport(member, tripId, 0);
        assertThat(locked.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(locked.getResponse().getContentAsString(), "$.code")).isEqualTo("TRIP_ENDED");

        long diaryId = ((Number) JsonPath.read(diary.getResponse().getContentAsString(), "$.diaryId")).longValue();
        mockMvc.perform(delete("/api/diaries/{diaryId}", diaryId).header("Authorization", member.bearer()))
                .andExpect(status().isNoContent());
        assertThat(changeTransport(member, tripId, 0).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("계획했다가 끝난 일반 여행은 여전히 읽기 전용이다")
    void plannedTripStaysReadOnlyAfterItEnds() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, clock.today().plusDays(1), 0));
        clock.advance(Duration.ofDays(3));

        MvcResult result = changeTransport(member, tripId, 0);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("TRIP_ENDED");
        mockMvc.perform(get("/api/trips/{tripId}/context", tripId).header("Authorization", member.bearer()))
                .andExpect(jsonPath("$.retroactive").value(false));
    }

    @Test
    @DisplayName("사후 기록 여행은 초대 링크 발급이 409 TRIP_ENDED다")
    void retroactiveTripCannotInvite() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, daysAgo(10), 1));

        MvcResult invite = fixtures.createInviteResult(member.accessToken(), tripId, "{}");

        assertThat(invite.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(invite.getResponse().getContentAsString(), "$.code")).isEqualTo("TRIP_ENDED");
    }

    @Test
    @DisplayName("내 여행 목록과 여행 조회에 retroactive가 나온다")
    void exposesRetroactiveFlag() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, daysAgo(10), 1));

        mockMvc.perform(get("/api/trips").header("Authorization", member.bearer()).param("period", "PAST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tripId").value(tripId))
                .andExpect(jsonPath("$[0].retroactive").value(true));
        mockMvc.perform(get("/api/trips/{tripId}/context", tripId).header("Authorization", member.bearer()))
                .andExpect(jsonPath("$.retroactive").value(true));
    }

    @Test
    @DisplayName("여행기 지도 핀에 여행 지역(regionSigCd·regionName)이 나오고 위치는 지역 중심이다")
    void mapPinCarriesRegion() throws Exception {
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47130', '경상북도', '경주시', 35.856, 129.225)");
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, daysAgo(10), 1));
        jdbc.update("update app.trip_plans set region_id = '47130' where id = ?", tripId);
        assertThat(createDiary(member, tripId).getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get("/api/me/travel-map").header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regionSigCd").value("47130"))
                .andExpect(jsonPath("$[0].regionName").value("경상북도 경주시"))
                .andExpect(jsonPath("$[0].lat").value(35.856))
                .andExpect(jsonPath("$[0].lng").value(129.225));
    }

    @Test
    @DisplayName("지역을 정하지 않고 만든 여행기 핀은 지역과 위치가 null이다")
    void mapPinWithoutRegion() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripId(createTrip(member, daysAgo(10), 1));
        assertThat(createDiary(member, tripId).getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get("/api/me/travel-map").header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regionSigCd").isEmpty())
                .andExpect(jsonPath("$[0].regionName").isEmpty())
                .andExpect(jsonPath("$[0].lat").isEmpty());
    }
}
