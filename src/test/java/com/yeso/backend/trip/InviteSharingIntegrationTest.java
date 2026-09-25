package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import com.yeso.backend.trip.infrastructure.CourseShareLinkRepository;
import com.yeso.backend.trip.infrastructure.ShareSessionCookieFactory;
import com.yeso.backend.trip.domain.TripInvitation;
import com.yeso.backend.trip.infrastructure.TripInvitationRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 초대와 읽기 전용 공유(2026-09-24 정책). 초대는 회원을 동등한 참여자로 들이고, 공유는
 * 참여하지 않은 사람에게 일정을 보여준다. 두 token 체계를 섞어 쓰는 경로까지 함께 검증한다.
 */
class InviteSharingIntegrationTest extends IntegrationTest {

    @Autowired
    private TripPlanRepository tripPlanRepository;

    @Autowired
    private TripInvitationRepository tripInvitationRepository;

    @Autowired
    private CourseShareLinkRepository courseShareLinkRepository;

    /** 설문을 마친 "만든사람" 회원의 access token. */
    private String creatorToken() throws Exception {
        return fixtures.onboardedMember("만든사람").accessToken();
    }

    private String member(String nickname) throws Exception {
        return fixtures.onboardedMember(nickname).accessToken();
    }

    /** 기본 여행: 오늘(고정 시계)부터 10일 뒤 출발, 2박. */
    private LocalDate defaultStartDate() {
        return clock.today().plusDays(10);
    }

    private Long createTrip(String token, LocalDate startDate) throws Exception {
        return fixtures.createTrip(token, startDate, 2);
    }

    private Long createTrip(String token) throws Exception {
        return createTrip(token, defaultStartDate());
    }

    private MvcResult createInvite(String token, Long tripId, String body) throws Exception {
        return fixtures.createInviteResult(token, tripId, body);
    }

    private String inviteToken(String token, Long tripId) throws Exception {
        return fixtures.inviteToken(token, tripId);
    }

    private MvcResult accept(String token, String inviteToken) throws Exception {
        return fixtures.acceptResult(token, inviteToken);
    }

    /** 만든 사람과 초대를 수락한 친구, 두 참여자가 있는 여행. */
    private record SharedTrip(Long tripId, String creator, String friend) {
    }

    private SharedTrip tripWithFriend() throws Exception {
        String creator = creatorToken();
        Long tripId = createTrip(creator);
        String friend = member("여행친구");
        fixtures.joinByInvite(creator, tripId, friend);
        return new SharedTrip(tripId, creator, friend);
    }

    private String shareLinkToken(String token, Long tripId) throws Exception {
        return fixtures.shareLinkToken(token, tripId);
    }

    private Cookie openShareSession(String shareToken) throws Exception {
        return fixtures.openShareSession(shareToken);
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰이 없으면 초대·공유 링크 관리 API와 초대 수락은 401 AUTH_UNAUTHENTICATED다")
        void inviteAndShareEndpoints_withoutToken_returnUnauthorized() throws Exception {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    post("/api/trips/{tripId}/invites", 1).contentType(MediaType.APPLICATION_JSON).content("{}"),
                    get("/api/trips/{tripId}/invites", 1),
                    delete("/api/trips/{tripId}/invites/{inviteId}", 1, 1),
                    post("/api/invites/{token}/accept", "iv_anything"),
                    post("/api/courses/{tripId}/share-links", 1).contentType(MediaType.APPLICATION_JSON).content("{}"),
                    get("/api/courses/{tripId}/share-links", 1),
                    delete("/api/courses/{tripId}/share-links/{linkId}", 1, 1));

            for (MockHttpServletRequestBuilder request : requests) {
                mockMvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            }
        }
    }

    @Nested
    @DisplayName("초대 링크 발급·목록·폐기")
    class InviteLinks {

        @Test
        @DisplayName("기본 7일 만료이고 permission이 없으며 token 원문은 발급 응답에만 실린다")
        void create_defaults() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);

            MvcResult result = createInvite(token, tripId, "{}");
            String body = result.getResponse().getContentAsString();

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body).doesNotContain("permission");
            String raw = JsonPath.read(body, "$.token");
            assertThat(raw).startsWith("iv_");
            assertThat(LocalDateTime.parse(JsonPath.<String>read(body, "$.expiresAt")))
                    .isEqualTo(clock.now().plusDays(7));
            assertThat(JsonPath.<String>read(body, "$.createdAt")).isNotBlank();

            mockMvc.perform(get("/api/trips/{tripId}/invites", tripId).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].token").doesNotExist())
                    .andExpect(jsonPath("$[0].revoked").value(false))
                    .andExpect(jsonPath("$[0].createdBy.nickname").value("만든사람"));
        }

        @Test
        @DisplayName("DB에는 원문이 아니라 SHA-256 해시만 저장한다")
        void create_storesHashOnly() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            String raw = inviteToken(token, tripId);

            List<TripInvitation> invitations = tripInvitationRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId);
            assertThat(invitations).hasSize(1);
            assertThat(invitations.get(0).getTokenHash()).isNotEqualTo(raw).hasSize(64);
            assertThat(tripInvitationRepository.findByTokenHash(raw)).isEmpty();
        }

        @Test
        @DisplayName("expiresInDays는 1과 30이 허용되고 0과 31은 400이다")
        void create_expiresInDaysBoundary() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);

            assertThat(createInvite(token, tripId, "{\"expiresInDays\":1}").getResponse().getStatus()).isEqualTo(201);
            assertThat(createInvite(token, tripId, "{\"expiresInDays\":30}").getResponse().getStatus()).isEqualTo(201);
            for (int days : new int[]{0, 31}) {
                MvcResult result = createInvite(token, tripId, "{\"expiresInDays\":%d}".formatted(days));
                assertThat(result.getResponse().getStatus()).isEqualTo(400);
                assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
                        .isEqualTo("INVALID_EXPIRES_IN_DAYS");
            }
        }

        @Test
        @DisplayName("참여자가 아니면 여행의 존재를 숨기고 404다")
        void create_notParticipant_returnsNotFound() throws Exception {
            Long tripId = createTrip(creatorToken());

            mockMvc.perform(post("/api/trips/{tripId}/invites", tripId)
                            .header("Authorization", ApiFixtures.bearer(creatorToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("초대를 수락한 참여자도 초대 링크를 만들 수 있다(동등한 권한)")
        void create_byInvitedParticipant() throws Exception {
            SharedTrip trip = tripWithFriend();

            assertThat(createInvite(trip.friend(), trip.tripId(), "{}").getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("폐기하면 204이고 이후 미리보기·수락은 410 INVITE_REVOKED다")
        void revoke_blocksFurtherUse() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            String body = createInvite(token, tripId, "{}").getResponse().getContentAsString();
            String raw = JsonPath.read(body, "$.token");
            Long inviteId = Long.valueOf(JsonPath.read(body, "$.id").toString());

            mockMvc.perform(delete("/api/trips/{tripId}/invites/{inviteId}", tripId, inviteId)
                            .header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/invites/{token}", raw))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("INVITE_REVOKED"));
            MvcResult accepted = accept(member("늦은친구"), raw);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(410);
        }

        @Test
        @DisplayName("다른 여행의 초대 ID로 폐기하면 404 INVITE_NOT_FOUND다")
        void revoke_crossTripId_returnsNotFound() throws Exception {
            String ownerA = creatorToken();
            String ownerB = creatorToken();
            Long tripA = createTrip(ownerA);
            Long tripB = createTrip(ownerB);
            Long inviteOfA = Long.valueOf(
                    JsonPath.read(createInvite(ownerA, tripA, "{}").getResponse().getContentAsString(), "$.id")
                            .toString());

            mockMvc.perform(delete("/api/trips/{tripId}/invites/{inviteId}", tripB, inviteOfA)
                            .header("Authorization", ApiFixtures.bearer(ownerB)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("초대 미리보기·수락")
    class Accept {

        @Test
        @DisplayName("미리보기는 로그인 없이 보낸 사람·기간·인원만 보여준다")
        void preview_public() throws Exception {
            String token = creatorToken();
            LocalDate startDate = defaultStartDate();
            Long tripId = createTrip(token, startDate);

            mockMvc.perform(get("/api/invites/{token}", inviteToken(token, tripId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.startDate").value(startDate.toString()))
                    .andExpect(jsonPath("$.inviterNickname").value("만든사람"))
                    .andExpect(jsonPath("$.participantCount").value(1))
                    .andExpect(jsonPath("$.regionName").isEmpty());
        }

        @Test
        @DisplayName("설문을 마친 회원이 수락하면 201과 여행 context를 받고 참여자가 된다")
        void accept_success() throws Exception {
            SharedTrip trip = tripWithFriend();

            mockMvc.perform(get("/api/trips/{id}/context", trip.tripId()).header("Authorization", ApiFixtures.bearer(trip.friend())))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/trips/{id}/participants", trip.tripId())
                            .header("Authorization", ApiFixtures.bearer(trip.friend())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[1].nickname").value("여행친구"))
                    .andExpect(jsonPath("$[1].isCreator").value(false));
            mockMvc.perform(get("/api/trips").header("Authorization", ApiFixtures.bearer(trip.friend())))
                    .andExpect(jsonPath("$[0].tripId").value(trip.tripId()));
        }

        @Test
        @DisplayName("이미 참여 중이면 200으로 같은 결과를 준다(멱등)")
        void accept_twice_isIdempotent() throws Exception {
            String creator = creatorToken();
            Long tripId = createTrip(creator);
            String raw = inviteToken(creator, tripId);
            String friend = member("여행친구");

            assertThat(accept(friend, raw).getResponse().getStatus()).isEqualTo(201);
            MvcResult again = accept(friend, raw);
            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(again.getResponse().getContentAsString(), "$.id").longValue())
                    .isEqualTo(tripId);
            assertThat(accept(creator, raw).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("설문을 마치지 않았으면 409 ONBOARDING_REQUIRED다")
        void accept_withoutOnboarding_returnsConflict() throws Exception {
            String creator = creatorToken();
            Long tripId = createTrip(creator);

            MvcResult result = accept(fixtures.signup("신규").accessToken(), inviteToken(creator, tripId));
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("ONBOARDING_REQUIRED");
        }

        @Test
        @DisplayName("수락자의 기존 여행과 날짜가 겹치면 409 TRIP_DATE_OVERLAP이다")
        void accept_overlapWithOwnTrip_returnsConflict() throws Exception {
            String creator = creatorToken();
            LocalDate startDate = defaultStartDate();
            Long tripId = createTrip(creator, startDate);
            String friend = member("여행친구");
            createTrip(friend, startDate.plusDays(1));

            MvcResult result = accept(friend, inviteToken(creator, tripId));
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("TRIP_DATE_OVERLAP");
        }

        @Test
        @DisplayName("참여한 여행의 기간은 수락자에게도 선택 불가다")
        void accept_blocksFriendsCalendar() throws Exception {
            SharedTrip trip = tripWithFriend();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", ApiFixtures.bearer(trip.friend()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":0,"transport":"WALK"}
                                    """.formatted(defaultStartDate().plusDays(1))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"));
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void accept_anonymous_returnsUnauthorized() throws Exception {
            String creator = creatorToken();
            Long tripId = createTrip(creator);

            mockMvc.perform(post("/api/invites/{token}/accept", inviteToken(creator, tripId)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("초대는 발급 7일 전까지 유효하고 7일이 되는 순간 410 INVITE_EXPIRED다")
        void accept_expired_returnsGone() throws Exception {
            String creator = creatorToken();
            Long tripId = createTrip(creator);
            String raw = inviteToken(creator, tripId);

            clock.advance(Duration.ofDays(7).minusSeconds(1));
            mockMvc.perform(get("/api/invites/{token}", raw))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true));

            clock.advance(Duration.ofSeconds(1));
            MvcResult result = accept(member("늦은친구"), raw);
            assertThat(result.getResponse().getStatus()).isEqualTo(410);
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("INVITE_EXPIRED");
        }

        @Test
        @DisplayName("종료된 여행은 초대 링크 발급과 수락이 409 TRIP_ENDED다")
        void endedTrip_blocksInviteAndAccept() throws Exception {
            String creator = creatorToken();
            Long tripId = createTrip(creator); // 10일 뒤 출발, 2박 → 12일 뒤 종료
            String raw = fixtures.inviteToken(creator, tripId, """
                    {"expiresInDays":30}
                    """);
            clock.setTo(defaultStartDate().plusDays(3)); // 종료일 다음 날(초대는 아직 유효)

            MvcResult created = createInvite(creator, tripId, "{}");
            assertThat(created.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(created.getResponse().getContentAsString(), "$.code")).isEqualTo("TRIP_ENDED");

            MvcResult accepted = accept(member("늦은친구"), raw);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(accepted.getResponse().getContentAsString(), "$.code")).isEqualTo("TRIP_ENDED");
        }

        @Test
        @DisplayName("공유 token을 초대 엔드포인트에 쓰면 TOKEN_AUDIENCE_MISMATCH다")
        void preview_shareToken_returnsAudienceMismatch() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);

            mockMvc.perform(get("/api/invites/{token}", shareLinkToken(token, tripId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_AUDIENCE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("탈퇴")
    class Leave {

        @Test
        @DisplayName("만든 사람이 탈퇴해도 남은 참여자의 여행은 유지되고, 마지막 참여자가 나가면 삭제된다")
        void leave_creatorThenFriend() throws Exception {
            SharedTrip trip = tripWithFriend();

            mockMvc.perform(delete("/api/trips/{id}/participants/me", trip.tripId())
                            .header("Authorization", ApiFixtures.bearer(trip.creator())))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/trips/{id}/context", trip.tripId()).header("Authorization", ApiFixtures.bearer(trip.creator())))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/trips/{id}/participants", trip.tripId())
                            .header("Authorization", ApiFixtures.bearer(trip.friend())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));

            mockMvc.perform(delete("/api/trips/{id}/participants/me", trip.tripId())
                            .header("Authorization", ApiFixtures.bearer(trip.friend())))
                    .andExpect(status().isNoContent());
            assertThat(tripPlanRepository.findById(trip.tripId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("공유 링크")
    class Sharing {

        @Test
        @DisplayName("open은 token을 HttpOnly cookie(Path=/api/shared)로 교환하고 token 없는 URL로 303 redirect한다")
        void open_exchangesTokenForCookie() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            String shareToken = shareLinkToken(token, tripId);

            MvcResult opened = mockMvc.perform(get("/api/shared/courses/{token}", shareToken))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/shared/courses"))
                    .andReturn();

            assertThat(opened.getResponse().getContentAsString()).doesNotContain(shareToken);
            Cookie cookie = opened.getResponse().getCookie(ShareSessionCookieFactory.COOKIE_NAME);
            assertThat(cookie).isNotNull();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getValue()).isNotEqualTo(shareToken);
            assertThat(cookie.getPath()).isEqualTo("/api/shared");
        }

        @Test
        @DisplayName("발급 응답은 permission 없이 token·만료·createdAt을 준다")
        void create_readOnlyLink() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);

            mockMvc.perform(post("/api/courses/{tripId}/share-links", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expiresInDays\":3}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.token").exists())
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.permission").doesNotExist());

            mockMvc.perform(get("/api/courses/{tripId}/share-links", tripId).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].createdBy.nickname").value("만든사람"))
                    .andExpect(jsonPath("$[0].permission").doesNotExist());
        }

        @Test
        @DisplayName("교환한 세션으로 일정을 VIEWER로 조회한다")
        void view_withSession() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            Cookie cookie = openShareSession(shareLinkToken(token, tripId));

            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tripId").value(tripId))
                    .andExpect(jsonPath("$.myRole").value("VIEWER"))
                    .andExpect(jsonPath("$.days.length()").value(0));
        }

        @Test
        @DisplayName("세션 cookie가 없으면 401 SHARE_SESSION_INVALID다")
        void view_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/shared/courses"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SHARE_SESSION_INVALID"));
        }

        @Test
        @DisplayName("링크를 폐기하면 204이고 이미 발급된 세션으로도 더는 볼 수 없다")
        void revoke_killsExistingSession() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            Cookie cookie = openShareSession(shareLinkToken(token, tripId));
            Long linkId = courseShareLinkRepository.findByTripPlanIdOrderByCreatedAtDesc(tripId).get(0).getId();

            mockMvc.perform(get("/api/shared/courses").cookie(cookie)).andExpect(status().isOk());
            mockMvc.perform(delete("/api/courses/{tripId}/share-links/{linkId}", tripId, linkId)
                            .header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("SHARE_LINK_REVOKED"));
        }

        @Test
        @DisplayName("링크가 만료되면 아직 유효한 세션도 410 SHARE_LINK_EXPIRED다")
        void expired_killsExistingSession() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            String shareToken = fixtures.shareLinkToken(token, tripId, """
                    {"expiresInDays":1}
                    """);

            clock.advance(Duration.ofHours(23)); // 링크 만료 1시간 전에 세션(2시간 유효)을 연다
            Cookie cookie = openShareSession(shareToken);
            mockMvc.perform(get("/api/shared/courses").cookie(cookie)).andExpect(status().isOk());

            clock.advance(Duration.ofHours(1)); // 링크 만료 시각, 세션은 1시간 남음
            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("SHARE_LINK_EXPIRED"));
        }

        @Test
        @DisplayName("share session은 2시간 뒤 만료되어 401 SHARE_SESSION_INVALID다")
        void session_expiresAfterTwoHours() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);
            Cookie cookie = openShareSession(shareLinkToken(token, tripId));

            clock.advance(Duration.ofHours(2).minusSeconds(1));
            mockMvc.perform(get("/api/shared/courses").cookie(cookie)).andExpect(status().isOk());

            clock.advance(Duration.ofSeconds(1));
            mockMvc.perform(get("/api/shared/courses").cookie(cookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SHARE_SESSION_INVALID"));
        }

        @Test
        @DisplayName("초대 token으로는 공유 링크를 열 수 없다")
        void open_inviteToken_returnsAudienceMismatch() throws Exception {
            String token = creatorToken();
            Long tripId = createTrip(token);

            mockMvc.perform(get("/api/shared/courses/{token}", inviteToken(token, tripId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_AUDIENCE_MISMATCH"));
        }

        @Test
        @DisplayName("참여자가 아니면 공유 링크를 만들 수 없고, 초대를 수락한 참여자는 만들 수 있다")
        void create_permission() throws Exception {
            SharedTrip trip = tripWithFriend();

            mockMvc.perform(post("/api/courses/{tripId}/share-links", trip.tripId())
                            .header("Authorization", ApiFixtures.bearer(creatorToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            assertThat(shareLinkToken(trip.friend(), trip.tripId())).startsWith("sl_");
        }
    }
}
