package com.yeso.backend.profile;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.profile.application.friend.FriendQueryService;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 친구 초대 링크와 친구 목록(docs/api/profile.md 2-4~2-10). */
class FriendIntegrationTest extends IntegrationTest {

    @Autowired
    private FriendQueryService friendQueryService;

    private MvcResult createLink(Member member, String body) throws Exception {
        return mockMvc.perform(post("/api/friend-links")
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult accept(Member member, String token) throws Exception {
        return fixtures.acceptFriendLinkResult(member.accessToken(), token);
    }

    private Long linkIdOf(Member member) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/friend-links").header("Authorization", member.bearer())).andReturn();
        return JsonPath.<Integer>read(result.getResponse().getContentAsString(), "$[0].id").longValue();
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰 없이 부르면 401 AUTH_UNAUTHENTICATED다")
        void withoutToken_returnsUnauthorized() throws Exception {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    post("/api/friend-links").contentType(MediaType.APPLICATION_JSON).content("{}"),
                    get("/api/friend-links"),
                    delete("/api/friend-links/1"),
                    post("/api/friend-links/by-token/fl_x/accept"),
                    get("/api/friends"),
                    delete("/api/friends/1"));
            for (MockHttpServletRequestBuilder request : requests) {
                mockMvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            }
        }
    }

    @Nested
    @DisplayName("2-4 친구 초대 링크 발급")
    class CreateLink {

        @Test
        @DisplayName("기본 7일짜리 fl_ 링크를 발급하고 token 원문을 준다")
        void create_default() throws Exception {
            Member member = fixtures.signup("보낸사람");

            mockMvc.perform(post("/api/friend-links")
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("fl_")))
                    .andExpect(jsonPath("$.expiresAt").value("2026-10-08T10:00:00"));
        }

        @Test
        @DisplayName("body를 생략해도 기본값으로 발급한다")
        void create_withoutBody() throws Exception {
            Member member = fixtures.signup();

            mockMvc.perform(post("/api/friend-links").header("Authorization", member.bearer()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("expiresInDays는 1과 30은 되고 0과 31은 400 INVALID_EXPIRES_IN_DAYS다")
        void create_expiresInDaysBoundary() throws Exception {
            Member member = fixtures.signup();

            assertThat(createLink(member, "{\"expiresInDays\":1}").getResponse().getStatus()).isEqualTo(201);
            assertThat(createLink(member, "{\"expiresInDays\":30}").getResponse().getStatus()).isEqualTo(201);
            for (String body : List.of("{\"expiresInDays\":0}", "{\"expiresInDays\":31}")) {
                mockMvc.perform(post("/api/friend-links")
                                .header("Authorization", member.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_EXPIRES_IN_DAYS"));
            }
        }
    }

    @Nested
    @DisplayName("2-5 내 친구 초대 링크 목록")
    class ListLinks {

        @Test
        @DisplayName("내 링크만 최신순으로, 새로 친구가 된 수와 폐기 여부를 함께 준다")
        void list_ownLinksWithCounts() throws Exception {
            Member owner = fixtures.signup("주인");
            Member stranger = fixtures.signup("남");
            fixtures.friendLinkToken(stranger.accessToken());
            String older = fixtures.friendLinkToken(owner.accessToken());
            fixtures.friendLinkToken(owner.accessToken());
            Member friend = fixtures.signup("친구");
            accept(friend, older);
            accept(friend, older); // 이미 친구인 재수락은 세지 않는다

            mockMvc.perform(get("/api/friend-links").header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].acceptedCount").value(0))
                    .andExpect(jsonPath("$[1].acceptedCount").value(1))
                    .andExpect(jsonPath("$[1].revoked").value(false))
                    .andExpect(jsonPath("$[0].token").doesNotExist());
        }
    }

    @Nested
    @DisplayName("2-6 친구 초대 링크 폐기")
    class RevokeLink {

        @Test
        @DisplayName("폐기하면 204이고 이후 수락은 410 FRIEND_LINK_REVOKED, 이미 맺은 친구는 그대로다")
        void revoke_blocksFurtherAccepts() throws Exception {
            Member owner = fixtures.signup("주인");
            String token = fixtures.friendLinkToken(owner.accessToken());
            Member early = fixtures.signup("먼저");
            accept(early, token);

            mockMvc.perform(delete("/api/friend-links/{id}", linkIdOf(owner)).header("Authorization", owner.bearer()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/friend-links/by-token/{token}/accept", token)
                            .header("Authorization", fixtures.signup("나중").bearer()))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("FRIEND_LINK_REVOKED"));
            assertThat(friendQueryService.areFriends(owner.userId(), early.userId())).isTrue();
        }

        @Test
        @DisplayName("다른 사람의 링크나 없는 링크는 404 FRIEND_LINK_NOT_FOUND다")
        void revoke_notMine_returnsNotFound() throws Exception {
            Member owner = fixtures.signup();
            fixtures.friendLinkToken(owner.accessToken());
            Member other = fixtures.signup();

            for (Long id : List.of(linkIdOf(owner), 999_999L)) {
                mockMvc.perform(delete("/api/friend-links/{id}", id).header("Authorization", other.bearer()))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("FRIEND_LINK_NOT_FOUND"));
            }
        }
    }

    @Nested
    @DisplayName("2-7 친구 초대 링크 미리보기")
    class Preview {

        @Test
        @DisplayName("로그인 없이 보낸 사람 닉네임만 보여준다")
        void preview_public() throws Exception {
            Member owner = fixtures.signup("보낸사람");
            String token = fixtures.friendLinkToken(owner.accessToken());

            mockMvc.perform(get("/api/friend-links/by-token/{token}", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.inviterNickname").value("보낸사람"));
        }

        @Test
        @DisplayName("없는 token은 404, 다른 용도의 token은 400 TOKEN_AUDIENCE_MISMATCH다")
        void preview_invalidTokens() throws Exception {
            mockMvc.perform(get("/api/friend-links/by-token/{token}", "fl_unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FRIEND_LINK_NOT_FOUND"));
            mockMvc.perform(get("/api/friend-links/by-token/{token}", "iv_something"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_AUDIENCE_MISMATCH"));
        }

        @Test
        @DisplayName("만료 1초 전까지는 유효하고 만료 시각부터 410 FRIEND_LINK_EXPIRED다")
        void preview_expiryBoundary() throws Exception {
            String token = fixtures.friendLinkToken(fixtures.signup().accessToken());

            clock.advance(Duration.ofDays(7).minusSeconds(1));
            mockMvc.perform(get("/api/friend-links/by-token/{token}", token)).andExpect(status().isOk());

            clock.advance(Duration.ofSeconds(1));
            mockMvc.perform(get("/api/friend-links/by-token/{token}", token))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("FRIEND_LINK_EXPIRED"));
        }
    }

    @Nested
    @DisplayName("2-8 친구 초대 수락")
    class Accept {

        @Test
        @DisplayName("수락하면 201과 상대를 받고 양쪽 친구 목록에 서로 보인다")
        void accept_success() throws Exception {
            Member owner = fixtures.signup("보낸사람");
            Member friend = fixtures.signup("받은사람");
            String token = fixtures.friendLinkToken(owner.accessToken());

            mockMvc.perform(post("/api/friend-links/by-token/{token}/accept", token).header("Authorization", friend.bearer()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(owner.userId()))
                    .andExpect(jsonPath("$.nickname").value("보낸사람"))
                    .andExpect(jsonPath("$.since").value("2026-10-01T10:00:00"));

            mockMvc.perform(get("/api/friends").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$[0].userId").value(friend.userId()));
            mockMvc.perform(get("/api/friends").header("Authorization", friend.bearer()))
                    .andExpect(jsonPath("$[0].userId").value(owner.userId()));
        }

        @Test
        @DisplayName("이미 친구면 200으로 같은 관계를 준다(멱등). 반대 방향 링크로 수락해도 마찬가지다")
        void accept_alreadyFriends_isIdempotent() throws Exception {
            Member a = fixtures.signup();
            Member b = fixtures.signup();
            String aLink = fixtures.friendLinkToken(a.accessToken());

            assertThat(accept(b, aLink).getResponse().getStatus()).isEqualTo(201);
            assertThat(accept(b, aLink).getResponse().getStatus()).isEqualTo(200);
            assertThat(accept(a, fixtures.friendLinkToken(b.accessToken())).getResponse().getStatus()).isEqualTo(200);
            mockMvc.perform(get("/api/friends").header("Authorization", a.bearer()))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("한 링크를 여러 명이 수락할 수 있다")
        void accept_manyPeople() throws Exception {
            Member owner = fixtures.signup();
            String token = fixtures.friendLinkToken(owner.accessToken());
            accept(fixtures.signup(), token);
            accept(fixtures.signup(), token);

            mockMvc.perform(get("/api/friends").header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("내 링크를 내가 수락하면 400 FRIEND_LINK_SELF다")
        void accept_ownLink_returnsBadRequest() throws Exception {
            Member owner = fixtures.signup();
            String token = fixtures.friendLinkToken(owner.accessToken());

            mockMvc.perform(post("/api/friend-links/by-token/{token}/accept", token).header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FRIEND_LINK_SELF"));
        }

        @Test
        @DisplayName("만료된 링크는 410 FRIEND_LINK_EXPIRED다")
        void accept_expired_returnsGone() throws Exception {
            String token = fixtures.friendLinkToken(fixtures.signup().accessToken());
            clock.advance(Duration.ofDays(7));

            mockMvc.perform(post("/api/friend-links/by-token/{token}/accept", token)
                            .header("Authorization", fixtures.signup().bearer()))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("FRIEND_LINK_EXPIRED"));
        }

        @Test
        @DisplayName("같은 두 사람이 서로의 링크를 동시에 수락해도 친구 관계는 하나만 생긴다")
        void accept_concurrentSamePair_createsOneFriendship() throws Exception {
            Member a = fixtures.signup();
            Member b = fixtures.signup();
            String aLink = fixtures.friendLinkToken(a.accessToken());
            String bLink = fixtures.friendLinkToken(b.accessToken());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            results.add(executor.submit(() -> {
                start.await();
                return accept(b, aLink).getResponse().getStatus();
            }));
            results.add(executor.submit(() -> {
                start.await();
                return accept(a, bLink).getResponse().getStatus();
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            executor.shutdown();

            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            mockMvc.perform(get("/api/friends").header("Authorization", a.bearer()))
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Nested
    @DisplayName("2-9 친구 목록")
    class ListFriends {

        @Test
        @DisplayName("친구가 된 순서 최신순이다")
        void list_newestFirst() throws Exception {
            Member me = fixtures.signup();
            Member first = fixtures.signup("먼저");
            Member second = fixtures.signup("나중");
            fixtures.makeFriends(me, first);
            clock.advance(Duration.ofMinutes(1));
            fixtures.makeFriends(me, second);

            mockMvc.perform(get("/api/friends").header("Authorization", me.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].nickname").value("나중"))
                    .andExpect(jsonPath("$[1].nickname").value("먼저"));
        }
    }

    @Nested
    @DisplayName("2-10 친구 끊기")
    class Unfriend {

        @Test
        @DisplayName("끊으면 204이고 양쪽 목록에서 사라지며, 새 링크로 다시 친구가 될 수 있다")
        void unfriend_success() throws Exception {
            Member a = fixtures.signup();
            Member b = fixtures.signup();
            fixtures.makeFriends(a, b);

            mockMvc.perform(delete("/api/friends/{userId}", b.userId()).header("Authorization", a.bearer()))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/friends").header("Authorization", b.bearer()))
                    .andExpect(jsonPath("$.length()").value(0));
            assertThat(friendQueryService.areFriends(a.userId(), b.userId())).isFalse();

            fixtures.makeFriends(b, a);
            assertThat(friendQueryService.areFriends(a.userId(), b.userId())).isTrue();
        }

        @Test
        @DisplayName("친구가 아니면 404 FRIEND_NOT_FOUND다")
        void unfriend_notFriend_returnsNotFound() throws Exception {
            Member a = fixtures.signup();
            Member b = fixtures.signup();

            mockMvc.perform(delete("/api/friends/{userId}", b.userId()).header("Authorization", a.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FRIEND_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("친구 여부 조회(다른 모듈용)")
    class Query {

        @Test
        @DisplayName("친구면 true, 아니거나 자기 자신이면 false다")
        void areFriends() throws Exception {
            Member a = fixtures.signup();
            Member b = fixtures.signup();
            Member c = fixtures.signup();
            fixtures.makeFriends(a, b);

            assertThat(friendQueryService.areFriends(a.userId(), b.userId())).isTrue();
            assertThat(friendQueryService.areFriends(b.userId(), a.userId())).isTrue();
            assertThat(friendQueryService.areFriends(a.userId(), c.userId())).isFalse();
            assertThat(friendQueryService.areFriends(a.userId(), a.userId())).isFalse();
        }
    }
}
