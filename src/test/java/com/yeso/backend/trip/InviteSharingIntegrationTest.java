package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.onboarding.infrastructure.EmbeddingClient;
import com.yeso.backend.onboarding.infrastructure.FakeEmbeddingClient;
import com.yeso.backend.trip.domain.CourseShareLink;
import com.yeso.backend.trip.domain.TripInvitation;
import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripParticipantStatus;
import com.yeso.backend.trip.domain.TripParticipantType;
import com.yeso.backend.trip.infrastructure.CourseShareLinkRepository;
import com.yeso.backend.trip.infrastructure.GuestSessionRepository;
import com.yeso.backend.trip.infrastructure.ShareSessionCookieFactory;
import com.yeso.backend.trip.infrastructure.TripInvitationRepository;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WORK-04 비회원 초대와 VIEW/EDIT 공유. 초대(참여·온보딩)와 공유(확정 일정 권한)는 별도 token
 * 체계이므로 두 계열을 섞어 쓰는 경로까지 함께 검증한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(InviteSharingIntegrationTest.FakeEmbeddingClientConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-only-secret-not-used-outside-automated-tests",
        "spring.jpa.properties.hibernate.default_schema=app"
})
class InviteSharingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("tripin_invite_test")
            .withUsername("tripin_test")
            .withPassword("tripin_test");

    @TestConfiguration
    static class FakeEmbeddingClientConfig {
        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripParticipantRepository tripParticipantRepository;

    @Autowired
    private TripInvitationRepository tripInvitationRepository;

    @Autowired
    private CourseShareLinkRepository courseShareLinkRepository;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private EmbeddingClient embeddingClient;

    private static int seq = 0;

    @BeforeEach
    void setUp() {
        ((FakeEmbeddingClient) embeddingClient).setMode(FakeEmbeddingClient.Mode.SUCCESS);
    }

    // ---------- fixtures ----------

    private String freshToken() throws Exception {
        String email = "invite" + (seq++) + "_" + System.nanoTime() + "@example.com";
        MvcResult signup = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","nickname":"주최자"}
                                """.formatted(email)))
                .andReturn();
        return JsonPath.read(signup.getResponse().getContentAsString(), "$.accessToken");
    }

    /** 회원마다 새 token을 쓰므로 날짜 중복 차단(WORK-03)에 걸리지 않는다. */
    private Long createTrip(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/trips")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"%s","nights":2,"transport":"CAR"}
                                """.formatted(LocalDate.now().plusDays(10))))
                .andReturn();
        return Long.valueOf(JsonPath.read(result.getResponse().getContentAsString(), "$.id").toString());
    }

    private MvcResult createInvite(String token, Long tripId, String body) throws Exception {
        return mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String inviteToken(String token, Long tripId) throws Exception {
        return JsonPath.read(createInvite(token, tripId, "{}").getResponse().getContentAsString(), "$.token");
    }

    private MvcResult join(String inviteToken, String displayName) throws Exception {
        return mockMvc.perform(post("/api/invites/{token}/participants", inviteToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"%s"}
                                """.formatted(displayName)))
                .andReturn();
    }

    private String shareLinkToken(String token, Long tripId, String permission) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/courses/{tripId}/share-links", tripId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permission":"%s"}
                                """.formatted(permission)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** open이 내려준 HttpOnly share_session cookie를 그대로 다음 요청에 실어 보낸다. */
    private Cookie openShareSession(String shareToken) throws Exception {
        return mockMvc.perform(get("/api/shared/courses/{token}", shareToken))
                .andExpect(status().isSeeOther())
                .andReturn()
                .getResponse()
                .getCookie(ShareSessionCookieFactory.COOKIE_NAME);
    }

    private static String onboardingBody() {
        Map<Integer, Integer> byNumber = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            byNumber.put(i, 1);
        }
        String answers = byNumber.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "{\"questionNumber\":%d,\"choice\":%d}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "[", "]"));
        return """
                {"questionVersion":"demo-mbti-v1","answers":%s,"scheduleDensity":"RELAXED"}
                """.formatted(answers);
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("초대 링크 생성")
    class CreateInvite {

        @Test
        @DisplayName("기본값은 VIEW 권한과 7일 만료이고 token 원문은 생성 응답에만 실린다")
        void create_defaults() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);

            MvcResult result = createInvite(token, tripId, "{}");
            String body = result.getResponse().getContentAsString();

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(JsonPath.<String>read(body, "$.permission")).isEqualTo("VIEW");
            String raw = JsonPath.read(body, "$.token");
            assertThat(raw).isNotBlank();
            assertThat(LocalDateTime.parse(JsonPath.<String>read(body, "$.expiresAt")))
                    .isCloseTo(LocalDateTime.now().plusDays(7), within(1, ChronoUnit.MINUTES));

            String listBody = mockMvc.perform(get("/api/trips/{tripId}/invites", tripId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(listBody).doesNotContain(raw);
        }

        @Test
        @DisplayName("DB에는 원문이 아니라 SHA-256 해시만 저장한다")
        void create_storesHashOnly() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            List<TripInvitation> invitations = tripInvitationRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId);
            assertThat(invitations).hasSize(1);
            assertThat(invitations.get(0).getTokenHash()).isNotEqualTo(raw).hasSize(64);
            assertThat(tripInvitationRepository.findByTokenHash(raw)).isEmpty();
        }

        @Test
        @DisplayName("permission=EDIT를 지정하면 그대로 저장한다")
        void create_editPermission() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);

            mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"EDIT\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.permission").value("EDIT"));
        }

        @Test
        @DisplayName("expiresInDays는 1과 30이 허용되고 0과 31은 400이다")
        void create_expiresInDaysBoundary() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);

            assertThat(createInvite(token, tripId, "{\"expiresInDays\":1}").getResponse().getStatus()).isEqualTo(201);
            assertThat(createInvite(token, tripId, "{\"expiresInDays\":30}").getResponse().getStatus()).isEqualTo(201);

            mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expiresInDays\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_EXPIRES_IN_DAYS"));

            mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expiresInDays\":31}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_EXPIRES_IN_DAYS"));
        }

        @Test
        @DisplayName("소유자가 아니면 여행의 존재를 숨기고 404다")
        void create_notOwner_returnsNotFound() throws Exception {
            Long tripId = createTrip(freshToken());
            String otherToken = freshToken();

            mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                            .header("Authorization", "Bearer " + otherToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("다른 여행의 초대 ID로 조회·수정하면 404다")
        void invite_crossTripId_returnsNotFound() throws Exception {
            String ownerA = freshToken();
            String ownerB = freshToken();
            Long tripA = createTrip(ownerA);
            Long tripB = createTrip(ownerB);
            Long inviteOfA = Long.valueOf(
                    JsonPath.read(createInvite(ownerA, tripA, "{}").getResponse().getContentAsString(), "$.id")
                            .toString());

            mockMvc.perform(get("/api/trips/{tripId}/invites/{inviteId}", tripB, inviteOfA)
                            .header("Authorization", "Bearer " + ownerB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));

            mockMvc.perform(patch("/api/trips/{tripId}/invites/{inviteId}", tripB, inviteOfA)
                            .header("Authorization", "Bearer " + ownerB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revoked\":true}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("초대 참여")
    class Join {

        @Test
        @DisplayName("표시 이름만으로 가입 없이 INVITED 참여자와 guest session이 생긴다")
        void join_success() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            MvcResult result = join(raw, "  동행이  ");
            String body = result.getResponse().getContentAsString();

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(JsonPath.<String>read(body, "$.displayName")).isEqualTo("동행이");
            assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("INVITED");

            Long participantId = Long.valueOf(JsonPath.read(body, "$.participantId").toString());
            TripParticipant participant = tripParticipantRepository.findById(participantId).orElseThrow();
            assertThat(participant.getParticipantType()).isEqualTo(TripParticipantType.GUEST);
            assertThat(participant.getUser()).isNull();
            assertThat(participant.getTripInvitationId()).isNotNull();

            String sessionToken = JsonPath.read(body, "$.guestSessionToken");
            assertThat(guestSessionRepository.findByTokenHash(sessionToken)).isEmpty();
        }

        @Test
        @DisplayName("표시 이름이 공백뿐이거나 trim 후 30자를 넘으면 400이다")
        void join_invalidDisplayName() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            assertThat(join(raw, "   ").getResponse().getStatus()).isEqualTo(400);
            assertThat(join(raw, "가".repeat(31)).getResponse().getStatus()).isEqualTo(400);
            assertThat(join(raw, "가".repeat(30)).getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("링크 하나로 동시에 참여해도 각자 별도 참여자와 세션을 받는다")
        void join_concurrent_createsDistinctParticipants() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            Set<Long> participantIds = ConcurrentHashMap.newKeySet();
            Set<String> sessionTokens = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        String body = join(raw, "손님" + index).getResponse().getContentAsString();
                        participantIds.add(Long.valueOf(JsonPath.read(body, "$.participantId").toString()));
                        sessionTokens.add(JsonPath.read(body, "$.guestSessionToken"));
                    } catch (Exception ignored) {
                        // 실패는 아래 크기 단언에서 드러난다.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(participantIds).hasSize(threads);
            assertThat(sessionTokens).hasSize(threads);
        }

        @Test
        @DisplayName("만료된 초대는 410 INVITE_EXPIRED다")
        void join_expired_returnsGone() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            TripInvitation invitation = tripInvitationRepository
                    .findByTripPlanIdOrderByCreatedAtDesc(tripId).get(0);
            invitation.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            tripInvitationRepository.save(invitation);

            mockMvc.perform(post("/api/invites/{token}/participants", raw)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"늦은손님\"}"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        }

        @Test
        @DisplayName("폐기된 초대는 410 INVITE_REVOKED다")
        void join_revoked_returnsGone() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            MvcResult created = createInvite(token, tripId, "{}");
            String body = created.getResponse().getContentAsString();
            String raw = JsonPath.read(body, "$.token");
            Long inviteId = Long.valueOf(JsonPath.read(body, "$.id").toString());

            mockMvc.perform(patch("/api/trips/{tripId}/invites/{inviteId}", tripId, inviteId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revoked\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revoked").value(true));

            mockMvc.perform(get("/api/invites/{token}", raw))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("INVITE_REVOKED"));
        }

        @Test
        @DisplayName("공유 token을 초대 엔드포인트에 쓰면 TOKEN_AUDIENCE_MISMATCH다")
        void join_shareToken_returnsAudienceMismatch() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String shareToken = shareLinkToken(token, tripId, "VIEW");

            mockMvc.perform(get("/api/invites/{token}", shareToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_AUDIENCE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("게스트 온보딩")
    class GuestOnboarding {

        @Test
        @DisplayName("guest session으로 제출하면 참여자가 READY가 된다")
        void submit_success() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String body = join(inviteToken(token, tripId), "동행이").getResponse().getContentAsString();
            Long participantId = Long.valueOf(JsonPath.read(body, "$.participantId").toString());
            String guestSession = JsonPath.read(body, "$.guestSessionToken");

            mockMvc.perform(post("/api/invite-participants/{id}/onboarding", participantId)
                            .header("Authorization", "Bearer " + guestSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(onboardingBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mbtiCode").exists());

            TripParticipant participant = tripParticipantRepository.findById(participantId).orElseThrow();
            assertThat(participant.getStatus()).isEqualTo(TripParticipantStatus.READY);
            assertThat(participant.getLatestOnboardingSubmissionId()).isNotNull();
        }

        @Test
        @DisplayName("다른 참여자의 온보딩은 제출할 수 없다")
        void submit_otherParticipant_returnsUnauthorized() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            String mine = join(raw, "나").getResponse().getContentAsString();
            String theirs = join(raw, "남").getResponse().getContentAsString();
            Long theirParticipantId = Long.valueOf(JsonPath.read(theirs, "$.participantId").toString());
            String mySession = JsonPath.read(mine, "$.guestSessionToken");

            mockMvc.perform(post("/api/invite-participants/{id}/onboarding", theirParticipantId)
                            .header("Authorization", "Bearer " + mySession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(onboardingBody()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("GUEST_SESSION_INVALID"));
        }

        @Test
        @DisplayName("초대 token을 guest session 대신 쓰면 TOKEN_AUDIENCE_MISMATCH다")
        void submit_inviteToken_returnsAudienceMismatch() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);
            Long participantId = Long.valueOf(
                    JsonPath.read(join(raw, "동행이").getResponse().getContentAsString(), "$.participantId")
                            .toString());

            mockMvc.perform(post("/api/invite-participants/{id}/onboarding", participantId)
                            .header("Authorization", "Bearer " + raw)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(onboardingBody()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_AUDIENCE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("공유 링크")
    class Sharing {

        @Test
        @DisplayName("open은 token을 HttpOnly cookie로 교환하고 token 없는 URL로 303 redirect한다")
        void open_exchangesTokenForCookie() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String shareToken = shareLinkToken(token, tripId, "VIEW");

            MvcResult opened = mockMvc.perform(get("/api/shared/courses/{token}", shareToken))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/shared/courses"))
                    .andReturn();

            assertThat(opened.getResponse().getHeader(HttpHeaders.LOCATION)).doesNotContain(shareToken);
            assertThat(opened.getResponse().getContentAsString()).doesNotContain(shareToken);

            Cookie cookie = opened.getResponse().getCookie(ShareSessionCookieFactory.COOKIE_NAME);
            assertThat(cookie).isNotNull();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getValue()).isNotEqualTo(shareToken);
        }

        @Test
        @DisplayName("교환한 세션으로 일정을 VIEW 권한으로 조회한다")
        void view_withSession() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            Cookie cookie = openShareSession(shareLinkToken(token, tripId, "VIEW"));

            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tripId").value(tripId))
                    .andExpect(jsonPath("$.permission").value("VIEW"));
        }

        @Test
        @DisplayName("세션 cookie가 없으면 401 SHARE_SESSION_INVALID다")
        void view_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/shared/courses"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SHARE_SESSION_INVALID"));
        }

        @Test
        @DisplayName("권한을 EDIT로 바꾸면 세션 조회 결과도 EDIT가 된다")
        void patch_permission_appliesToSession() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            String shareToken = shareLinkToken(token, tripId, "VIEW");
            Long linkId = courseShareLinkRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).get(0).getId();

            mockMvc.perform(patch("/api/courses/{tripId}/share-links/{linkId}", tripId, linkId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"EDIT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permission").value("EDIT"));

            mockMvc.perform(get("/api/shared/courses").cookie(openShareSession(shareToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permission").value("EDIT"));
        }

        @Test
        @DisplayName("알 수 없는 permission 값은 400이다")
        void create_invalidPermission_returnsBadRequest() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);

            mockMvc.perform(post("/api/courses/{tripId}/share-links", tripId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"ADMIN\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SHARE_PERMISSION"));
        }

        @Test
        @DisplayName("링크를 폐기하면 이미 발급된 세션으로도 더는 볼 수 없다")
        void revoke_killsExistingSession() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            Cookie cookie = openShareSession(shareLinkToken(token, tripId, "VIEW"));
            Long linkId = courseShareLinkRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).get(0).getId();

            mockMvc.perform(get("/api/shared/courses").cookie(cookie)).andExpect(status().isOk());

            mockMvc.perform(patch("/api/courses/{tripId}/share-links/{linkId}", tripId, linkId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"revoked\":true}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("SHARE_LINK_REVOKED"));
        }

        @Test
        @DisplayName("링크가 만료되면 이미 발급된 세션도 410 SHARE_LINK_EXPIRED다")
        void expired_killsExistingSession() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);
            Cookie cookie = openShareSession(shareLinkToken(token, tripId, "VIEW"));

            CourseShareLink link = courseShareLinkRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).get(0);
            link.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            courseShareLinkRepository.save(link);

            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("SHARE_LINK_EXPIRED"));
        }

        @Test
        @DisplayName("초대 token으로는 공유 링크를 열 수 없다")
        void open_inviteToken_returnsAudienceMismatch() throws Exception {
            String token = freshToken();
            Long tripId = createTrip(token);

            mockMvc.perform(get("/api/shared/courses/{token}", inviteToken(token, tripId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_AUDIENCE_MISMATCH"));
        }

        @Test
        @DisplayName("소유자가 아니면 공유 링크를 만들 수 없다")
        void create_notOwner_returnsNotFound() throws Exception {
            Long tripId = createTrip(freshToken());
            String otherToken = freshToken();

            mockMvc.perform(post("/api/courses/{tripId}/share-links", tripId)
                            .header("Authorization", "Bearer " + otherToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"VIEW\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }
}
