package com.yeso.backend.trip;

import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 지역 정하기(docs/api/trip.md 3-7). 지역·관광지 데이터는 SQL로 준비한다(RegionEligibilityIntegrationTest와 같은 방식). */
class RegionDrawIntegrationTest extends IntegrationTest {

    private static final String GYEONGJU = "47130";
    private static final String JEONJU = "45210";

    @Autowired
    private JdbcTemplate jdbc;

    private void region(String sigCd, double lat, double lng) {
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values (?, ?, ?, ?, ?)",
                sigCd, "도" + sigCd, "시" + sigCd, lat, lng);
    }

    private void content(String sigCd) {
        jdbc.update("""
                insert into app.region_contents (region_id, title, introduction, status, hero_image_validation_status,
                                                 prompt_version, reviewed_at)
                values (?, '소개', '본문', 'APPROVED', 'VALID', 1, cast('2026-09-01 10:00:00' as timestamp))
                """, sigCd);
    }

    private void attraction(String sigCd) {
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, addr)
                values ('관광지', '관광지', ?, '설명', 35.8, 129.2, 12, '주소') returning id
                """, Long.class, sigCd);
        jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                id, "https://img.example/" + id + ".jpg");
    }

    /** 1일 RELAXED로 추첨 가능한 지역(관광지 5곳). */
    private void readyRegion(String sigCd, double lat, double lng) {
        region(sigCd, lat, lng);
        content(sigCd);
        for (int i = 0; i < 5; i++) {
            attraction(sigCd);
        }
    }

    private LocalDate inDays(int days) {
        return clock.today().plusDays(days);
    }

    @Nested
    @DisplayName("랜덤 추첨")
    class Random {

        @Test
        @DisplayName("추첨 가능한 지역이 하나면 그 지역으로 정해지고 version이 오른다")
        void random_singleCandidate_success() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0); // 당일치기 = 1일

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"RANDOM","version":0}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionSigCd").value(GYEONGJU))
                    .andExpect(jsonPath("$.regionSelection").value("RANDOM"))
                    .andExpect(jsonPath("$.scheduleDensity").value("RELAXED"))
                    .andExpect(jsonPath("$.candidateCount").value(1))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("추첨 가능한 지역이 없으면 422 DRAW_NO_ELIGIBLE_REGION이다")
        void random_noEligibleRegion_returnsUnprocessable() throws Exception {
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"RANDOM\",\"version\":0}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DRAW_NO_ELIGIBLE_REGION"));
        }
    }

    @Nested
    @DisplayName("조건 추첨")
    class Conditional {

        @Test
        @DisplayName("DISTANCE를 요청했지만 출발지가 없으면 무시하고 균등 추첨 경고를 반환한다")
        void conditional_distanceWithoutOrigin_isIgnored() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"CONDITIONAL","conditions":["DISTANCE"],"version":0}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionSigCd").value(GYEONGJU))
                    .andExpect(jsonPath("$.appliedConditions.length()").value(0))
                    .andExpect(jsonPath("$.ignoredConditions[0].condition").value("DISTANCE"))
                    .andExpect(jsonPath("$.ignoredConditions[0].reason").value("ORIGIN_MISSING"))
                    .andExpect(jsonPath("$.warnings[0]").value("ALL_CONDITIONS_IGNORED"));
        }

        @Test
        @DisplayName("MY_TASTE는 관광지 취향 벡터가 아직 없어 항상 무시된다")
        void conditional_myTaste_alwaysIgnoredForNow() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"CONDITIONAL","conditions":["MY_TASTE"],"version":0}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ignoredConditions[0].condition").value("MY_TASTE"))
                    .andExpect(jsonPath("$.ignoredConditions[0].reason").value("TASTE_NOT_READY"));
        }

        @Test
        @DisplayName("조건이 비었으면 400 DRAW_NO_CONDITION_SELECTED다")
        void conditional_noCondition_returnsBadRequest() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"CONDITIONAL","conditions":[],"version":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DRAW_NO_CONDITION_SELECTED"));
        }
    }

    @Nested
    @DisplayName("직접 선택")
    class Manual {

        @Test
        @DisplayName("추첨 가능한 지역을 고르면 그 지역으로 정해진다")
        void manual_eligibleRegion_success() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            readyRegion(JEONJU, 35.82, 127.15);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"MANUAL","sigCd":"%s","version":0}
                                    """.formatted(JEONJU)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionSigCd").value(JEONJU))
                    .andExpect(jsonPath("$.regionSelection").value("MANUAL"));
        }

        @Test
        @DisplayName("부적격 지역을 고르면 422 DRAW_REGION_NOT_ELIGIBLE이다")
        void manual_ineligibleRegion_returnsUnprocessable() throws Exception {
            region(GYEONGJU, 35.85, 129.22); // 소개문·관광지 없음 = 부적격
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"MANUAL","sigCd":"%s","version":0}
                                    """.formatted(GYEONGJU)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DRAW_REGION_NOT_ELIGIBLE"));
        }

        @Test
        @DisplayName("없는 지역이면 404 REGION_NOT_FOUND다")
        void manual_unknownRegion_returnsNotFound() throws Exception {
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"MANUAL","sigCd":"99999","version":0}
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("검증·잠금·버전")
    class Validation {

        @Test
        @DisplayName("mode가 유효하지 않으면 400 DRAW_INVALID_MODE다")
        void invalidMode_returnsBadRequest() throws Exception {
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"LOTTERY\",\"version\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DRAW_INVALID_MODE"));
        }

        @Test
        @DisplayName("버전이 다르면 409 TRIP_VERSION_CONFLICT다")
        void staleVersion_returnsConflict() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"RANDOM\",\"version\":5}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("코스가 있는 여행은 replaceCourse 없이 바꾸면 409 TRIP_CONTEXT_LOCKED다")
        void hasCourse_withoutReplaceCourse_returnsConflict() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            readyRegion(JEONJU, 35.82, 127.15);
            Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), inDays(5), 0);
            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                    .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"mode":"MANUAL","sigCd":"%s","version":0}
                            """.formatted(GYEONGJU)));
            jdbc.update("""
                    insert into app.course_items (trip_plan_id, day_index, order_index, kind, meal_type, stay_minutes)
                    values (?, 0, 0, 'MEAL', 'LUNCH', 60)
                    """, tripId);

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"MANUAL","sigCd":"%s","version":1}
                                    """.formatted(JEONJU)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_CONTEXT_LOCKED"));

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mode":"MANUAL","sigCd":"%s","replaceCourse":true,"version":1}
                                    """.formatted(JEONJU)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionSigCd").value(JEONJU));
        }

        @Test
        @DisplayName("참여하지 않은 여행은 404 TRIP_NOT_FOUND다")
        void notParticipant_returnsNotFound() throws Exception {
            Member owner = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(owner.accessToken(), inDays(5), 0);
            Member other = fixtures.onboardedMember();

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(other.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"RANDOM\",\"version\":0}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("종료된 여행은 409 TRIP_ENDED다")
        void endedTrip_returnsConflict() throws Exception {
            readyRegion(GYEONGJU, 35.85, 129.22);
            Member member = fixtures.onboardedMember();
            LocalDate startDate = inDays(5);
            Long tripId = fixtures.createTrip(member.accessToken(), startDate, 0);
            clock.setTo(startDate.plusDays(1));

            mockMvc.perform(post("/api/trips/{tripId}/region", tripId)
                            .header("Authorization", ApiFixtures.bearer(member.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"RANDOM\",\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_ENDED"));
        }
    }
}
