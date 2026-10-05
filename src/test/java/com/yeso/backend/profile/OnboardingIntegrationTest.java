package com.yeso.backend.profile;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.profile.infrastructure.FakeEmbeddingClient;
import com.yeso.backend.profile.application.onboarding.OnboardingQueryService;
import com.yeso.backend.attraction.infrastructure.RegionRepository;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnboardingIntegrationTest extends IntegrationTest {

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private OnboardingQueryService onboardingQueryService;

    @BeforeEach
    void seedRegions() {
        regionRepository.save(new Region("11110", "서울특별시", "종로구"));
        regionRepository.save(new Region("41110", "경기도", "수원시"));
    }

    private String accessToken() throws Exception {
        return fixtures.signup().accessToken();
    }

    /** 기본은 전부 choice=1이고, overrides로 특정 문항만 덮어쓴다. */
    private static String answersJson(Map<Integer, Integer> overrides) {
        Map<Integer, Integer> byNumber = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            byNumber.put(i, 1);
        }
        byNumber.putAll(overrides);
        return byNumber.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "{\"questionNumber\":%d,\"choice\":%d}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String submissionBody(
            String questionVersion, String scheduleDensity, Map<Integer, Integer> answerOverrides,
            String experienceTagsJson, String likedTripsJson) {
        return """
                {"questionVersion":"%s","answers":%s,"scheduleDensity":"%s","experienceTags":%s,"likedTrips":%s}
                """.formatted(
                questionVersion, answersJson(answerOverrides), scheduleDensity, experienceTagsJson, likedTripsJson);
    }

    private static String aiHubSubmissionBody(String stylesJson, String motivesJson, String likedRegionsJson) {
        return """
                {"questionVersion":"aihub-traveler-v1","scheduleDensity":"RELAXED","excludeTags":["물놀이"],
                 "travelStyles":%s,"travelMotives":%s,"likedRegions":%s}
                """.formatted(stylesJson, motivesJson, likedRegionsJson);
    }

    @Nested
    @DisplayName("질문 조회")
    class Questions {

        @Test
        @DisplayName("인증 없이 AI Hub 스타일·동기 문항과 근거 수준을 반환한다")
        void questions_isPublicAndMatchesFixture() throws Exception {
            mockMvc.perform(get("/api/onboarding/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.questionVersion").value("aihub-traveler-v1"))
                    .andExpect(jsonPath("$.travelStyles.length()").value(4))
                    .andExpect(jsonPath("$.travelStyles[0].number").value(1))
                    .andExpect(jsonPath("$.travelStyles[0].evidence").value("OFFICIAL"))
                    .andExpect(jsonPath("$.travelStyles[1].number").value(3))
                    .andExpect(jsonPath("$.travelStyles[1].evidence").value("INFERRED"))
                    .andExpect(jsonPath("$.travelMotives.length()").value(9))
                    .andExpect(jsonPath("$.maxTravelMotives").value(3))
                    .andExpect(jsonPath("$.maxLikedRegions").value(3))
                    .andExpect(jsonPath("$.excludeTags.length()").value(4))
                    .andExpect(jsonPath("$.scheduleDensityOptions[0]").value("RELAXED"));
        }
    }

    @Nested
    @DisplayName("제출")
    class Submit {

        @Test
        @DisplayName("구형 템플릿 벡터는 새 관광지 템플릿과 섞지 않고 최신 설문을 기다린다")
        void submit_legacyProfileVector_isNotUsedWithCurrentAttractionTemplate() throws Exception {
            var member = fixtures.signup();
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", "[]")))
                    .andExpect(status().isCreated());

            assertThat(onboardingQueryService.findTasteVector(member.userId())).isEmpty();
        }

        @Test
        @DisplayName("AI Hub 설문은 구조화된 취향을 저장하고 템플릿 v2로 전달한다")
        void submit_aiHubProfile_usesTemplateV2() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(aiHubSubmissionBody(
                                    "{\"1\":1,\"3\":4,\"5\":6,\"6\":7}", "[2,7]", "[\"11110\"]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.questionVersion").value("aihub-traveler-v1"))
                    .andExpect(jsonPath("$.mbtiCode").doesNotExist())
                    .andExpect(jsonPath("$.travelStyles['1']").value(1))
                    .andExpect(jsonPath("$.travelStyles['5']").value(6))
                    .andExpect(jsonPath("$.travelMotives[0]").value(2))
                    .andExpect(jsonPath("$.likedRegions[0]").value("11110"))
                    .andExpect(jsonPath("$.tasteStatus").value("READY"));

            assertThat(fakeEmbeddingClient.lastRequest().templateVersion()).isEqualTo(2);
            assertThat(fakeEmbeddingClient.lastRequest().modelVersion()).isEqualTo("mminilm-l12-v1");
            assertThat(fakeEmbeddingClient.lastRequest().profile().travelStyles())
                    .containsEntry(1, 1).containsEntry(3, 4).containsEntry(5, 6).containsEntry(6, 7);
            assertThat(fakeEmbeddingClient.lastRequest().profile().travelMotives()).containsExactly(2, 7);
            assertThat(fakeEmbeddingClient.lastRequest().profile().likedRegions())
                    .containsExactly("서울특별시 종로구");
        }

        @Test
        @DisplayName("AI Hub 스타일은 정해진 4개 항목 각각 1~7만 허용한다")
        void submit_aiHub_invalidStyles_returnsDomainCode() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(aiHubSubmissionBody("{\"1\":1,\"3\":4,\"5\":6}", "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_TRAVEL_STYLES"));
        }

        @Test
        @DisplayName("AI Hub 여행 동기는 1~9 중 중복 없이 최대 3개를 허용한다")
        void submit_aiHub_invalidMotives_returnsDomainCode() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(aiHubSubmissionBody("{\"1\":1,\"3\":4,\"5\":6,\"6\":7}", "[2,2]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_TRAVEL_MOTIVE"));
        }

        @Test
        @DisplayName("AI Hub 선호 지역은 등록된 SIG_CD만 받는다")
        void submit_aiHub_unknownLikedRegion_returnsDomainCode() throws Exception {
            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(aiHubSubmissionBody(
                                    "{\"1\":1,\"3\":4,\"5\":6,\"6\":7}", "[]", "[\"99999\"]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_REGION_NOT_FOUND"));
        }

        @Test
        @DisplayName("유효한 제출이면 201과 mbtiCode·profileText·tasteStatus를 반환한다")
        void submit_success() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody(
                                    "demo-mbti-v1", "RELAXED", Map.of(), "[\"바다\",\"카페\"]", "[]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.submissionId").isNotEmpty())
                    .andExpect(jsonPath("$.mbtiCode").value("ESFP"))
                    .andExpect(jsonPath("$.scheduleDensity").value("RELAXED"))
                    .andExpect(jsonPath("$.onboardingCompleted").value(true))
                    .andExpect(jsonPath("$.tasteStatus").value("READY"))
                    .andExpect(jsonPath("$.profileText").value(org.hamcrest.Matchers.containsString("MBTI: ESFP")));
        }

        @Test
        @DisplayName("질문 버전이 다르면 400 INVALID_QUESTION_VERSION을 반환한다")
        void submit_invalidQuestionVersion() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("old-version", "RELAXED", Map.of(), "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_QUESTION_VERSION"));
        }

        @Test
        @DisplayName("답이 하나 빠지면 400 MISSING_QUESTION_ANSWER를 반환한다")
        void submit_missingAnswer() throws Exception {
            String token = accessToken();
            String answers = IntStream.rangeClosed(1, 11)
                    .mapToObj(i -> "{\"questionNumber\":%d,\"choice\":1}".formatted(i))
                    .collect(Collectors.joining(",", "[", "]"));

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"questionVersion":"demo-mbti-v1","answers":%s,"scheduleDensity":"RELAXED"}
                                    """.formatted(answers)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_MISSING_QUESTION_ANSWER"));
        }

        @Test
        @DisplayName("같은 문항에 답이 두 번 오면 400 DUPLICATE_QUESTION_ANSWER를 반환한다")
        void submit_duplicateAnswer() throws Exception {
            String token = accessToken();
            String answerEntries = "{\"questionNumber\":1,\"choice\":1},{\"questionNumber\":1,\"choice\":2},"
                    + IntStream.rangeClosed(2, 12)
                    .mapToObj(i -> "{\"questionNumber\":%d,\"choice\":1}".formatted(i))
                    .collect(Collectors.joining(","));

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"questionVersion":"demo-mbti-v1","answers":[%s],"scheduleDensity":"RELAXED"}
                                    """.formatted(answerEntries)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_DUPLICATE_QUESTION_ANSWER"));
        }

        @Test
        @DisplayName("choice가 1/2가 아니면 400 INVALID_CHOICE를 반환한다")
        void submit_invalidChoice() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(1, 3), "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_CHOICE"));
        }

        @Test
        @DisplayName("scheduleDensity가 RELAXED/PACKED가 아니면 400 INVALID_SCHEDULE_DENSITY를 반환한다")
        void submit_invalidScheduleDensity() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "SUPER_PACKED", Map.of(), "[]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_INVALID_SCHEDULE_DENSITY"));
        }

        @Test
        @DisplayName("PACKED도 유효한 값으로 허용된다")
        void submit_packedDensity_isAccepted() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "PACKED", Map.of(), "[]", "[]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scheduleDensity").value("PACKED"));
        }

        @Test
        @DisplayName("경험 태그가 정확히 5개면 허용된다")
        void submit_exactlyFiveExperienceTags_isAccepted() throws Exception {
            String token = accessToken();
            String tags = "[\"자연\",\"바다\",\"산\",\"산책\",\"골목\"]";

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), tags, "[]")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("경험 태그가 6개면 400 TOO_MANY_EXPERIENCE_TAGS를 반환한다")
        void submit_tooManyExperienceTags() throws Exception {
            String token = accessToken();
            String tags = "[\"자연\",\"바다\",\"산\",\"산책\",\"골목\",\"역사\"]";

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), tags, "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_TOO_MANY_EXPERIENCE_TAGS"));
        }

        @Test
        @DisplayName("알 수 없는 태그면 400 UNKNOWN_TAG를 반환한다")
        void submit_unknownTag() throws Exception {
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[\"우주여행\"]", "[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_UNKNOWN_TAG"));
        }

        @Test
        @DisplayName("존재하지 않는 지역이면 400 REGION_NOT_FOUND를 반환한다")
        void submit_unknownRegion() throws Exception {
            String token = accessToken();
            String likedTrips = "[{\"sigCd\":\"99999\",\"note\":\"test\"}]";

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", likedTrips)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_REGION_NOT_FOUND"));
        }

        @Test
        @DisplayName("같은 지역을 두 번 담으면 400 DUPLICATE_LIKED_REGION을 반환한다")
        void submit_duplicateLikedRegion() throws Exception {
            String token = accessToken();
            String likedTrips = "[{\"sigCd\":\"11110\"},{\"sigCd\":\"11110\"}]";

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", likedTrips)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_DUPLICATE_LIKED_REGION"));
        }

        @Test
        @DisplayName("31개의 좋았던 여행지를 담으면 400 TOO_MANY_LIKED_REGIONS를 반환한다")
        void submit_tooManyLikedRegions() throws Exception {
            String token = accessToken();
            String likedTrips = IntStream.range(0, 31)
                    .mapToObj(i -> "{\"sigCd\":\"%05d\"}".formatted(i))
                    .collect(Collectors.joining(",", "[", "]"));

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", likedTrips)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_TOO_MANY_LIKED_REGIONS"));
        }

        @Test
        @DisplayName("좋았던 여행지가 정확히 30개면 허용된다")
        void submit_exactlyThirtyLikedRegions_isAccepted() throws Exception {
            String token = accessToken();
            for (int i = 0; i < 30; i++) {
                String sigCd = "%05d".formatted(90000 + i);
                regionRepository.save(new Region(sigCd, "테스트도", "테스트시" + i));
            }
            String likedTrips = IntStream.range(0, 30)
                    .mapToObj(i -> "{\"sigCd\":\"%05d\"}".formatted(90000 + i))
                    .collect(Collectors.joining(",", "[", "]"));

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", likedTrips)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("유효한 좋았던 여행지는 저장되고 profileText에 SIG_CD 순서로 반영된다")
        void submit_withLikedTrips_success() throws Exception {
            String token = accessToken();
            String likedTrips = """
                    [{"sigCd":"41110","note":"좋았어요","tags":["역사"]},{"sigCd":"11110"}]
                    """;

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", likedTrips)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.profileText").value(org.hamcrest.Matchers.containsString("11110")))
                    .andExpect(jsonPath("$.profileText").value(org.hamcrest.Matchers.containsString("41110(역사):좋았어요")));
        }

        @Test
        @DisplayName("재검사하면 새 submissionId가 생기고 /me는 최신 결과를 반환한다")
        void submit_retake_updatesLatestPointer() throws Exception {
            String token = accessToken();

            MvcResult first = mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", "[]")))
                    .andReturn();
            String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.submissionId");

            MvcResult second = mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "PACKED", Map.of(), "[]", "[]")))
                    .andReturn();
            String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.submissionId");

            assertThat(secondId).isNotEqualTo(firstId);

            mockMvc.perform(get("/api/onboarding/me").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.onboardingCompleted").value(true))
                    .andExpect(jsonPath("$.submission.submissionId").value(secondId))
                    .andExpect(jsonPath("$.submission.scheduleDensity").value("PACKED"));
        }

        @Test
        @DisplayName("아직 제출한 적 없으면 /me는 onboardingCompleted=false를 반환한다")
        void me_beforeAnySubmission_returnsNotCompleted() throws Exception {
            String token = accessToken();

            mockMvc.perform(get("/api/onboarding/me").header("Authorization", ApiFixtures.bearer(token)))
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
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", "[]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasteStatus").value("PENDING"));
        }

        @Test
        @DisplayName("영구 실패(4xx)면 FAILED가 되고 submission은 성공한다")
        void submit_embeddingPermanentFailure_marksFailedButSubmissionSucceeds() throws Exception {
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.PERMANENT);
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", "[]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasteStatus").value("FAILED"));
        }

        @Test
        @DisplayName("dimension이 기대값과 다르면 FAILED가 되고 submission은 성공한다")
        void submit_embeddingDimensionMismatch_marksFailedButSubmissionSucceeds() throws Exception {
            fakeEmbeddingClient.setMode(FakeEmbeddingClient.Mode.DIMENSION_MISMATCH);
            String token = accessToken();

            mockMvc.perform(post("/api/onboarding/submissions")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionBody("demo-mbti-v1", "RELAXED", Map.of(), "[]", "[]")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasteStatus").value("FAILED"));
        }
    }
}
