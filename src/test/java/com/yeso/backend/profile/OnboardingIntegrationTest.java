package com.yeso.backend.profile;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.profile.application.onboarding.OnboardingEmbeddingRunner;
import com.yeso.backend.profile.infrastructure.FakeEmbeddingClient;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnboardingIntegrationTest extends IntegrationTest {

    private static final String STYLES = "{\"1\":1,\"3\":4,\"5\":6,\"6\":7}";

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private OnboardingEmbeddingRunner embeddingRunner;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRegions() {
        regionRepository.save(new Region("11110", "서울특별시", "종로구"));
        regionRepository.save(new Region("41110", "경기도", "수원시"));
        regionRepository.save(new Region("41130", "경기도", "성남시"));
        regionRepository.save(new Region("47130", "경상북도", "경주시"));
    }

    private String accessToken() throws Exception {
        return fixtures.signup().accessToken();
    }

    /** 1~12번 기본 choice=1, overrides로 덮어쓰고 null 값은 그 문항을 뺀다. */
    private static String mbtiJson(Map<Integer, Integer> overrides) {
        Map<Integer, Integer> byNumber = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            byNumber.put(i, 1);
        }
        byNumber.putAll(overrides);
        return byNumber.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "\"%d\":%d".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String v2Body(String scheduleDensity, String mbtiJson, String stylesJson, String motivesJson,
                                 String likedRegionsJson) {
        return """
                {"questionVersion":"aihub-traveler-v2","scheduleDensity":"%s","excludeTags":["물놀이"],
                 "travelStyles":%s,"travelMotives":%s,"likedRegions":%s,"mbtiAnswers":%s}
                """.formatted(scheduleDensity, stylesJson, motivesJson, likedRegionsJson, mbtiJson);
    }

    private static String v2Body() {
        return v2Body("RELAXED", mbtiJson(Map.of()), STYLES, "[2,7]", "[\"11110\"]");
    }

    private static String v1Body(String stylesJson, String motivesJson, String likedRegionsJson) {
        return """
                {"questionVersion":"aihub-traveler-v1","scheduleDensity":"RELAXED","excludeTags":["물놀이"],
                 "travelStyles":%s,"travelMotives":%s,"likedRegions":%s}
                """.formatted(stylesJson, motivesJson, likedRegionsJson);
    }

    private MvcResult submit(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/onboarding/submissions")
                        .header("Authorization", ApiFixtures.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Nested
    @DisplayName("질문 조회")
    class Questions {

        @Test
        @DisplayName("인증 없이 v2 문항(AI Hub 스타일·동기 + 여행 MBTI 12문항)을 반환하고 MBTI 글자 매핑은 숨긴다")
        void questions_isPublicAndMatchesFixture() throws Exception {
            mockMvc.perform(get("/api/onboarding/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.questionVersion").value("aihub-traveler-v2"))
                    .andExpect(jsonPath("$.travelStyles.length()").value(4))
                    .andExpect(jsonPath("$.travelStyles[0].number").value(1))
                    .andExpect(jsonPath("$.travelStyles[0].evidence").value("OFFICIAL"))
                    .andExpect(jsonPath("$.travelStyles[1].number").value(3))
                    .andExpect(jsonPath("$.travelStyles[1].evidence").value("INFERRED"))
                    .andExpect(jsonPath("$.travelMotives.length()").value(9))
                    .andExpect(jsonPath("$.maxTravelMotives").value(3))
                    .andExpect(jsonPath("$.maxLikedRegions").value(3))
                    .andExpect(jsonPath("$.excludeTags.length()").value(4))
                    .andExpect(jsonPath("$.scheduleDensityOptions[0]").value("RELAXED"))
                    .andExpect(jsonPath("$.mbtiQuestions.length()").value(12))
                    .andExpect(jsonPath("$.mbtiQuestions[0].number").value(1))
                    .andExpect(jsonPath("$.mbtiQuestions[0].question").value("여행을 떠날 때 계획은"))
                    .andExpect(jsonPath("$.mbtiQuestions[0].choices.length()").value(2))
                    .andExpect(jsonPath("$.mbtiQuestions[0].choices[1].choice").value(2))
                    .andExpect(jsonPath("$.mbtiQuestions[0].choices[0].letter").doesNotExist());
        }
    }

    @Nested
    @DisplayName("제출 v2")
    class SubmitV2 {

        @Test
        @DisplayName("v2 제출은 mbtiCode를 계산해 저장·반환하고, 임베딩에는 MBTI 없이 템플릿 v2로 보낸다")
        void submit_v2_storesMbtiCode() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.submissionId").isNotEmpty())
                    .andExpect(jsonPath("$.questionVersion").value("aihub-traveler-v2"))
                    .andExpect(jsonPath("$.mbtiCode").value("ESFP"))
                    .andExpect(jsonPath("$.scheduleDensity").value("RELAXED"))
                    .andExpect(jsonPath("$.travelStyles['5']").value(6))
                    .andExpect(jsonPath("$.likedRegions[0]").value("11110"))
                    .andExpect(jsonPath("$.onboardingCompleted").value(true))
                    .andExpect(jsonPath("$.tasteStatus").value("READY"));

            assertThat(fakeEmbeddingClient.lastRequest().templateVersion()).isEqualTo(2);
            assertThat(fakeEmbeddingClient.lastRequest().profile().travelStyles())
                    .containsEntry(1, 1).containsEntry(3, 4).containsEntry(5, 6).containsEntry(6, 7);
            assertThat(fakeEmbeddingClient.lastRequest().profile().travelMotives()).containsExactly(2, 7);
            assertThat(fakeEmbeddingClient.lastRequest().profile().likedRegions()).containsExactly("서울특별시 종로구");
            assertThat(jdbc.queryForObject(
                    "select mbti_answers::text from app.onboarding_submissions where mbti_code = 'ESFP'", String.class))
                    .contains("\"12\": 1");
        }

        @Test
        @DisplayName("다른 답이면 다른 유형이 된다 (모두 2 → INTJ)")
        void submit_v2_allSecondChoices() throws Exception {
            Map<Integer, Integer> allTwo = new HashMap<>();
            for (int i = 1; i <= 12; i++) {
                allTwo.put(i, 2);
            }
            MvcResult result = submit(accessToken(), v2Body("RELAXED", mbtiJson(allTwo), STYLES, "[]", "[]"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.mbtiCode")).isEqualTo("INTJ");
        }

        @Test
        @DisplayName("MBTI 답이 하나 빠지면 400 ONBOARDING_MISSING_QUESTION_ANSWER")
        void submit_v2_missingMbtiAnswer() throws Exception {
            Map<Integer, Integer> missing = new HashMap<>();
            missing.put(12, null);
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body("RELAXED", mbtiJson(missing), STYLES, "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_MISSING_QUESTION_ANSWER"));
        }

        @Test
        @DisplayName("MBTI 답이 없으면(필드 없음) 400 ONBOARDING_MISSING_QUESTION_ANSWER")
        void submit_v2_withoutMbtiAnswers() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v1Body(STYLES, "[]", "[]").replace("aihub-traveler-v1", "aihub-traveler-v2")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_MISSING_QUESTION_ANSWER"));
        }

        @Test
        @DisplayName("MBTI 답이 1·2가 아니거나 문항 번호가 1~12 밖이면 400 ONBOARDING_INVALID_CHOICE")
        void submit_v2_invalidMbtiChoice() throws Exception {
            for (Map<Integer, Integer> bad : java.util.List.of(Map.of(1, 3), Map.of(13, 1), Map.of(0, 1))) {
                mockMvc.perform(post("/api/onboarding/submissions")
                                .header("Authorization", ApiFixtures.bearer(accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(v2Body("RELAXED", mbtiJson(bad), STYLES, "[]", "[]")))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_CHOICE"));
            }
        }

        @Test
        @DisplayName("AI Hub 스타일은 정해진 4개 항목 각각 1~7만 허용한다")
        void submit_invalidStyles_returnsDomainCode() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body("RELAXED", mbtiJson(Map.of()), "{\"1\":1,\"3\":4,\"5\":6}", "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_TRAVEL_STYLES"));
        }

        @Test
        @DisplayName("AI Hub 여행 동기는 1~9 중 중복 없이 최대 3개를 허용한다")
        void submit_invalidMotives_returnsDomainCode() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body("RELAXED", mbtiJson(Map.of()), STYLES, "[2,2]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_TRAVEL_MOTIVE"));
        }

        @Test
        @DisplayName("선호 지역은 등록된 SIG_CD만, 중복 없이, 최대 3개까지 받는다")
        void submit_likedRegionRules() throws Exception {
            Map<String, String> cases = Map.of(
                    "[\"99999\"]", "ONBOARDING_REGION_NOT_FOUND",
                    "[\"11110\",\"11110\"]", "ONBOARDING_DUPLICATE_LIKED_REGION",
                    "[\"11110\",\"41110\",\"41130\",\"47130\"]", "ONBOARDING_TOO_MANY_LIKED_REGIONS");
            for (var c : cases.entrySet()) {
                mockMvc.perform(post("/api/onboarding/submissions")
                                .header("Authorization", ApiFixtures.bearer(accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(v2Body("RELAXED", mbtiJson(Map.of()), STYLES, "[]", c.getKey())))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value(c.getValue()));
            }
            assertThat(submit(accessToken(), v2Body("RELAXED", mbtiJson(Map.of()), STYLES, "[]",
                    "[\"11110\",\"41110\",\"41130\"]")).getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("scheduleDensity가 RELAXED/PACKED가 아니면 400, PACKED는 허용한다")
        void submit_scheduleDensity() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body("SUPER_PACKED", mbtiJson(Map.of()), STYLES, "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_SCHEDULE_DENSITY"));
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body("PACKED", mbtiJson(Map.of()), STYLES, "[]", "[]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scheduleDensity").value("PACKED"));
        }
    }

    @Nested
    @DisplayName("설문 버전")
    class Versions {

        @Test
        @DisplayName("v1 제출은 계속 받고 mbtiCode는 null이다")
        void submit_v1_isAcceptedWithoutMbti() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v1Body(STYLES, "[2,7]", "[\"11110\"]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.questionVersion").value("aihub-traveler-v1"))
                    .andExpect(jsonPath("$.mbtiCode").doesNotExist())
                    .andExpect(jsonPath("$.tasteStatus").value("READY"));
            assertThat(fakeEmbeddingClient.lastRequest().templateVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("구형 demo-mbti-v1과 모르는 버전은 400 ONBOARDING_INVALID_QUESTION_VERSION")
        void submit_legacyOrUnknownVersion_isRejected() throws Exception {
            for (String version : java.util.List.of("demo-mbti-v1", "old-version")) {
                mockMvc.perform(post("/api/onboarding/submissions")
                                .header("Authorization", ApiFixtures.bearer(accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(v2Body().replace("aihub-traveler-v2", version)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_QUESTION_VERSION"));
            }
        }

        @Test
        @DisplayName("재검사하면 새 submissionId가 생기고 /me는 최신 결과를 반환한다")
        void submit_retake_updatesLatestPointer() throws Exception {
            String token = accessToken();
            String firstId = JsonPath.read(submit(token, v2Body()).getResponse().getContentAsString(), "$.submissionId");
            String secondId = JsonPath.read(submit(token, v2Body("PACKED", mbtiJson(Map.of()), STYLES, "[]", "[]"))
                    .getResponse().getContentAsString(), "$.submissionId");

            assertThat(secondId).isNotEqualTo(firstId);
            mockMvc.perform(get("/api/onboarding/me").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.onboardingCompleted").value(true))
                    .andExpect(jsonPath("$.submission.submissionId").value(secondId))
                    .andExpect(jsonPath("$.submission.scheduleDensity").value("PACKED"))
                    .andExpect(jsonPath("$.submission.mbtiCode").value("ESFP"));
        }

        @Test
        @DisplayName("아직 제출한 적 없으면 /me는 onboardingCompleted=false를 반환한다")
        void me_beforeAnySubmission_returnsNotCompleted() throws Exception {
            mockMvc.perform(get("/api/onboarding/me").header("Authorization", ApiFixtures.bearer(accessToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.onboardingCompleted").value(false))
                    .andExpect(jsonPath("$.submission").doesNotExist());
        }
    }

    @Nested
    @DisplayName("임베딩 job 상태")
    class EmbeddingStatus {

        @Test
        @DisplayName("일시 장애(timeout)면 PENDING으로 남고 submission은 성공한다")
        void submit_embeddingTransientFailure_staysPendingButSubmissionSucceeds() throws Exception {
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.TRANSIENT);
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasteStatus").value("PENDING"));
        }

        @Test
        @DisplayName("영구 실패(4xx)면 FAILED가 되고 submission은 성공한다")
        void submit_embeddingPermanentFailure_marksFailedButSubmissionSucceeds() throws Exception {
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.PERMANENT);
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasteStatus").value("FAILED"));
        }

        @Test
        @DisplayName("dimension이 기대값과 다르면 FAILED가 되고 submission은 성공한다")
        void submit_embeddingDimensionMismatch_marksFailedButSubmissionSucceeds() throws Exception {
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.DIMENSION_MISMATCH);
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(v2Body()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasteStatus").value("FAILED"));
        }

        @Test
        @DisplayName("남아 있던 구형 템플릿 1 job은 AI를 부르지 않고 영구 실패로 끝낸다")
        void legacyTemplateJob_failsPermanentlyWithoutCallingAi() throws Exception {
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.TRANSIENT);
            String submissionId = JsonPath.read(submit(accessToken(), v2Body()).getResponse().getContentAsString(),
                    "$.submissionId");
            jdbc.update("update app.embedding_jobs set template_version = 1 where submission_id = ?::uuid", submissionId);
            fakeEmbeddingClient.reset();

            embeddingRunner.runJob(UUID.fromString(submissionId));

            assertThat(jdbc.queryForObject("select status from app.embedding_jobs where submission_id = ?::uuid",
                    String.class, submissionId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select last_error_code from app.embedding_jobs where submission_id = ?::uuid",
                    String.class, submissionId)).isEqualTo("UNSUPPORTED_TEMPLATE_VERSION");
            assertThat(fakeEmbeddingClient.lastRequest()).isNull();
        }
    }
}
