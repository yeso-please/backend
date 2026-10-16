package com.yeso.backend.trip;

import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 지역 먼저 고르기(D1·7-5·3-2·3-3), 이동수단 선택값(D2), 밀도 자동(D3-2), 성향 맞춤 문장(D13-3·7-6), 대표 관광지(D13-2). */
class RegionFirstFlowIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    /** MBTI 12문항 모두 두 번째 보기 → INTJ(J → PACKED). */
    private static final String V2_ONBOARDING_INTJ = """
            {"questionVersion":"aihub-traveler-v2","travelStyles":{"1":4,"3":4,"5":4,"6":4},"travelMotives":[],
             "likedRegions":[],"mbtiAnswers":{"1":2,"2":2,"3":2,"4":2,"5":2,"6":2,"7":2,"8":2,"9":2,"10":2,"11":2,"12":2}}
            """;

    @BeforeEach
    void setUp() {
        // 강릉: 추천 가능 관광지 6곳 → RELAXED 1일(5곳)만 된다. 경주: 0곳 → 추첨 불가.
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('51150', '강원특별자치도', '강릉시', 37.75, 128.9)");
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47130', '경상북도', '경주시', 35.85, 129.2)");
        for (int i = 0; i < 6; i++) {
            Long id = jdbc.queryForObject("""
                    insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, source_content_id)
                    values (?, '관광지', '51150', '바다 산책', ?, 128.9, 12, ?) returning id
                    """, Long.class, "강릉 명소" + i, 37.7 + i * 0.01, UUID.randomUUID().toString());
            jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                    id, "https://image.example/" + id);
        }
    }

    private Member v2Member(String nickname) throws Exception {
        Member member = fixtures.signup(nickname);
        mockMvc.perform(post("/api/onboarding/submissions").header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(V2_ONBOARDING_INTJ))
                .andExpect(status().isCreated());
        return member;
    }

    @Nested
    @DisplayName("7-5 지역 먼저 뽑기")
    class PreDraw {

        @Test
        @DisplayName("저장 없이 1일 기준 추첨 가능 지역에서 뽑고, 만들 수 있는 최대 박수를 준다")
        void random() throws Exception {
            Member member = fixtures.onboardedMember();
            mockMvc.perform(post("/api/regions/draw").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"RANDOM\",\"scheduleDensity\":\"RELAXED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sigCd").value("51150"))
                    .andExpect(jsonPath("$.maxNights").value(0))
                    .andExpect(jsonPath("$.candidateCount").value(1));
            assertThat(jdbc.queryForObject("select count(*) from app.trip_plans", Integer.class)).isZero();
        }

        @Test
        @DisplayName("조건 추첨에서 출발지가 없으면 거리 조건을 무시한다. MANUAL은 400이다")
        void conditional() throws Exception {
            Member member = fixtures.onboardedMember();
            mockMvc.perform(post("/api/regions/draw").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CONDITIONAL\",\"conditions\":[\"DISTANCE\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ignoredConditions[0].reason").value("ORIGIN_MISSING"))
                    .andExpect(jsonPath("$.warnings[0]").value("ALL_CONDITIONS_IGNORED"));
            mockMvc.perform(post("/api/regions/draw").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MANUAL\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DRAW_INVALID_MODE"));
        }
    }

    @Nested
    @DisplayName("3-2·3-3 지역과 함께 여행 만들기")
    class CreateWithRegion {

        @Test
        @DisplayName("이동수단을 생략하면 CAR, 지역을 주면 지역·선택 방식·밀도까지 저장한다")
        void createWithRegion() throws Exception {
            Member member = fixtures.onboardedMember();
            mockMvc.perform(post("/api/trips").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"startDate":"%s","nights":0,"regionSigCd":"51150","regionSelection":"RANDOM"}
                                    """.formatted(clock.today().plusDays(5))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transport").value("CAR"))
                    .andExpect(jsonPath("$.regionSigCd").value("51150"))
                    .andExpect(jsonPath("$.regionSelection").value("RANDOM"))
                    .andExpect(jsonPath("$.scheduleDensity").value("RELAXED"));
        }

        @Test
        @DisplayName("그 기간에 코스를 못 만드는 지역이면 422 DRAW_REGION_NOT_ELIGIBLE, 없는 지역은 404다")
        void ineligible() throws Exception {
            Member member = fixtures.onboardedMember();
            String start = clock.today().plusDays(5).toString();
            mockMvc.perform(post("/api/trips").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"" + start + "\",\"nights\":2,\"regionSigCd\":\"51150\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DRAW_REGION_NOT_ELIGIBLE"));
            mockMvc.perform(post("/api/trips").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"" + start + "\",\"nights\":0,\"regionSigCd\":\"99999\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
            assertThat(jdbc.queryForObject("select count(*) from app.trip_plans", Integer.class)).isZero();
        }

        @Test
        @DisplayName("3-2에 지역을 주면 그 기간에 코스를 만들 수 있는지 알려 준다")
        void checkWithRegion() throws Exception {
            Member member = fixtures.onboardedMember();
            String start = clock.today().plusDays(5).toString();
            mockMvc.perform(post("/api/trips/context/check").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"" + start + "\",\"nights\":0,\"sigCd\":\"51150\"}"))
                    .andExpect(jsonPath("$.regionEligible").value(true));
            mockMvc.perform(post("/api/trips/context/check").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"" + start + "\",\"nights\":1,\"sigCd\":\"51150\"}"))
                    .andExpect(jsonPath("$.regionEligible").value(false));
            mockMvc.perform(post("/api/trips/context/check").header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"" + start + "\",\"nights\":1}"))
                    .andExpect(jsonPath("$.regionEligible").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("밀도·성향")
    class Persona {

        @Test
        @DisplayName("v2 설문에서 밀도를 생략하면 MBTI J → PACKED다")
        void densityFromMbti() throws Exception {
            Member member = v2Member("계획형");
            assertThat(jdbc.queryForObject(
                    "select schedule_density from app.onboarding_submissions where user_id = ?", String.class, member.userId()))
                    .isEqualTo("PACKED");
        }

        @Test
        @DisplayName("7-6: MBTI가 없으면 문장이 없고, ai가 없으면 규칙 문장, ai가 있으면 LLM 문장을 기억해 다시 부르지 않는다")
        void regionMatch() throws Exception {
            Member v1 = fixtures.onboardedMember();
            mockMvc.perform(get("/api/regions/51150/match").header("Authorization", v1.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.text").value(nullValue()));

            Member member = v2Member("탐험가");
            mockMvc.perform(get("/api/regions/51150/match").header("Authorization", member.bearer()))
                    .andExpect(jsonPath("$.mbtiCode").value("INTJ"))
                    .andExpect(jsonPath("$.personaName").value("효율적인 설계자"))
                    .andExpect(jsonPath("$.textSource").value("RULE"))
                    .andExpect(jsonPath("$.aiGenerated").value(false));

            fakeAiTextClient.respond(true);
            mockMvc.perform(get("/api/regions/51150/match").header("Authorization", member.bearer()))
                    .andExpect(jsonPath("$.textSource").value("LLM"))
                    .andExpect(jsonPath("$.text").value("효율적인 설계자에게 어울리는 강원특별자치도 강릉시이에요."));
            int calls = fakeAiTextClient.requests().size();
            mockMvc.perform(get("/api/regions/51150/match").header("Authorization", member.bearer()))
                    .andExpect(jsonPath("$.textSource").value("LLM"));
            assertThat(fakeAiTextClient.requests()).hasSize(calls);
        }

        @Test
        @DisplayName("7-2 대표 관광지는 승인된 소개가 없어도 대표 관광지 규칙으로 최대 3곳 나온다")
        void landmarksFallback() throws Exception {
            Member member = fixtures.onboardedMember();
            mockMvc.perform(get("/api/regions/51150/card").header("Authorization", member.bearer()))
                    .andExpect(jsonPath("$.landmarks.length()").value(3))
                    .andExpect(jsonPath("$.landmarks[0].oneLine").value(nullValue()));
        }
    }
}
