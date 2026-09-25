package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 친구 직접 초대와 받은 초대함(docs/api/trip.md 4-6~4-8, 4-14, 4-15). 수락은 초대 링크 수락과 같은 참여 경로라
 * 정원·날짜 겹침·설문 조건을 함께 검증하고, 초대 링크로 먼저 참여했을 때 대기 초대가 정리되는지도 본다.
 */
class FriendInviteIntegrationTest extends IntegrationTest {

    /** 기본 여행: 오늘(고정 시계)부터 10일 뒤 출발, 2박. */
    private LocalDate defaultStartDate() {
        return clock.today().plusDays(10);
    }

    /** 여행을 만든 회원과 그 회원의 친구(아직 참여 전). */
    private record TripWithFriend(Long tripId, Member creator, Member friend) {
    }

    private TripWithFriend tripWithFriend() throws Exception {
        Member creator = fixtures.onboardedMember("만든사람");
        Member friend = fixtures.onboardedMember("여행친구");
        fixtures.makeFriends(creator, friend);
        Long tripId = fixtures.createTrip(creator.accessToken(), defaultStartDate(), 2);
        return new TripWithFriend(tripId, creator, friend);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Member member) throws Exception {
        return mockMvc.perform(request.header("Authorization", member.bearer()));
    }

    private ResultActions invite(Member inviter, Long tripId, Long friendUserId) throws Exception {
        return perform(post("/api/trips/{tripId}/friend-invites", tripId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"friendUserId":%d}
                        """.formatted(friendUserId)), inviter);
    }

    private ResultActions received(Member member) throws Exception {
        return perform(get("/api/me/trip-invites"), member);
    }

    private ResultActions acceptInvite(Member member, Long inviteId) throws Exception {
        return perform(post("/api/me/trip-invites/{id}/accept", inviteId), member);
    }

    private ResultActions declineInvite(Member member, Long inviteId) throws Exception {
        return perform(post("/api/me/trip-invites/{id}/decline", inviteId), member);
    }

    private ResultActions sent(Member member, Long tripId) throws Exception {
        return perform(get("/api/trips/{tripId}/friend-invites", tripId), member);
    }

    private ResultActions cancel(Member member, Long tripId, Long inviteId) throws Exception {
        return perform(delete("/api/trips/{tripId}/friend-invites/{inviteId}", tripId, inviteId), member);
    }

    private Long inviteId(TripWithFriend trip) throws Exception {
        return fixtures.friendInviteId(trip.creator().accessToken(), trip.tripId(), trip.friend().userId());
    }

    /** 만든 사람의 친구이면서 초대 링크로 여행에 들어온 참여자. */
    private Member joinedFriendOf(TripWithFriend trip, String nickname) throws Exception {
        Member member = fixtures.onboardedMember(nickname);
        fixtures.makeFriends(trip.creator(), member);
        fixtures.joinByInvite(trip.creator().accessToken(), trip.tripId(), member.accessToken());
        return member;
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰이 없으면 친구 초대 API는 모두 401 AUTH_UNAUTHENTICATED다")
        void friendInviteEndpoints_withoutToken_returnUnauthorized() throws Exception {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    post("/api/trips/{tripId}/friend-invites", 1).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"friendUserId\":1}"),
                    get("/api/trips/{tripId}/friend-invites", 1),
                    delete("/api/trips/{tripId}/friend-invites/{inviteId}", 1, 1),
                    get("/api/me/trip-invites"),
                    post("/api/me/trip-invites/{id}/accept", 1),
                    post("/api/me/trip-invites/{id}/decline", 1));

            for (MockHttpServletRequestBuilder request : requests) {
                mockMvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            }
        }
    }

    @Nested
    @DisplayName("4-6 친구 초대")
    class Invite {

        @Test
        @DisplayName("친구를 초대하면 201과 대기 중인 초대를 준다")
        void invite_friend_returnsCreated() throws Exception {
            TripWithFriend trip = tripWithFriend();

            invite(trip.creator(), trip.tripId(), trip.friend().userId())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.tripId").value(trip.tripId()))
                    .andExpect(jsonPath("$.invitee.userId").value(trip.friend().userId()))
                    .andExpect(jsonPath("$.invitee.nickname").value("여행친구"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("대기 중인 초대가 있으면 다른 참여자가 보내도 새로 만들지 않고 기존 초대를 200으로 준다")
        void invite_pendingExists_returnsExistingWithOk() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Member other = joinedFriendOf(trip, "다른참여자");
            fixtures.makeFriends(other, trip.friend());
            Long inviteId = inviteId(trip);

            invite(other, trip.tripId(), trip.friend().userId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(inviteId))
                    .andExpect(jsonPath("$.status").value("PENDING"));
            sent(trip.creator(), trip.tripId()).andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("거절·취소된 뒤에는 다시 초대할 수 있고 새 초대(201)가 생긴다")
        void invite_afterDeclineOrCancel_createsNewInvite() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long declined = inviteId(trip);
            declineInvite(trip.friend(), declined).andExpect(status().isNoContent());

            Long cancelled = inviteId(trip);
            assertThat(cancelled).isNotEqualTo(declined);
            cancel(trip.creator(), trip.tripId(), cancelled).andExpect(status().isNoContent());

            invite(trip.creator(), trip.tripId(), trip.friend().userId())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(cancelled.intValue())));
        }

        @Test
        @DisplayName("friendUserId가 없으면 400 COMMON_INVALID_REQUEST와 fieldErrors다")
        void invite_missingFriendUserId_returnsBadRequest() throws Exception {
            TripWithFriend trip = tripWithFriend();

            perform(post("/api/trips/{tripId}/friend-invites", trip.tripId())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), trip.creator())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("friendUserId"));
        }

        @Test
        @DisplayName("참여하지 않은 여행이나 없는 여행이면 404 TRIP_NOT_FOUND다")
        void invite_notParticipant_returnsTripNotFound() throws Exception {
            TripWithFriend trip = tripWithFriend();

            invite(trip.friend(), trip.tripId(), trip.creator().userId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            invite(trip.creator(), 999_999L, trip.friend().userId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("친구가 아닌 회원이나 나 자신을 초대하면 404 FRIEND_NOT_FOUND다")
        void invite_notFriend_returnsFriendNotFound() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Member stranger = fixtures.onboardedMember("모르는사람");

            invite(trip.creator(), trip.tripId(), stranger.userId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FRIEND_NOT_FOUND"));
            invite(trip.creator(), trip.tripId(), trip.creator().userId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FRIEND_NOT_FOUND"));
        }

        @Test
        @DisplayName("이미 참여 중인 친구를 초대하면 409 INVITE_ALREADY_HANDLED다")
        void invite_alreadyParticipant_returnsConflict() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Member joined = joinedFriendOf(trip, "이미참여");

            invite(trip.creator(), trip.tripId(), joined.userId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITE_ALREADY_HANDLED"));
        }

        @Test
        @DisplayName("종료된 여행에서는 409 TRIP_ENDED다")
        void invite_endedTrip_returnsTripEnded() throws Exception {
            TripWithFriend trip = tripWithFriend();
            clock.setTo(defaultStartDate().plusDays(3));

            invite(trip.creator(), trip.tripId(), trip.friend().userId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_ENDED"));
        }

        @Test
        @DisplayName("두 참여자가 같은 친구를 동시에 초대해도 대기 중인 초대는 하나만 생긴다")
        void invite_concurrently_createsSinglePendingInvite() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Member other = joinedFriendOf(trip, "다른참여자");
            fixtures.makeFriends(other, trip.friend());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            try {
                for (Member inviter : List.of(trip.creator(), other)) {
                    futures.add(executor.submit(() -> {
                        ready.await();
                        return fixtures.friendInviteResult(inviter.accessToken(), trip.tripId(), trip.friend().userId());
                    }));
                }
                ready.countDown();
                List<Integer> statuses = new ArrayList<>();
                List<Long> ids = new ArrayList<>();
                for (Future<MvcResult> future : futures) {
                    MvcResult result = future.get(30, TimeUnit.SECONDS);
                    statuses.add(result.getResponse().getStatus());
                    ids.add(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue());
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 200);
                assertThat(ids.get(0)).isEqualTo(ids.get(1));
            } finally {
                executor.shutdownNow();
            }
            sent(trip.creator(), trip.tripId()).andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("4-7 받은 초대 목록")
    class Received {

        @Test
        @DisplayName("대기 중인 초대만 최신순으로, 대체 제목·보낸 사람·날짜 겹침 여부와 함께 준다")
        void received_listsPendingInvitesNewestFirst() throws Exception {
            TripWithFriend first = tripWithFriend();
            Long firstInvite = inviteId(first);
            Member second = fixtures.onboardedMember("두번째");
            fixtures.makeFriends(second, first.friend());
            Long secondTrip = fixtures.createTrip(second.accessToken(), defaultStartDate().plusDays(20), 0);
            Long secondInvite = fixtures.friendInviteId(second.accessToken(), secondTrip, first.friend().userId());

            received(first.friend())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(secondInvite))
                    .andExpect(jsonPath("$[0].trip.tripId").value(secondTrip))
                    .andExpect(jsonPath("$[0].trip.title").value("10월 31일 당일 여행"))
                    .andExpect(jsonPath("$[0].inviter.nickname").value("두번째"))
                    .andExpect(jsonPath("$[1].id").value(firstInvite))
                    .andExpect(jsonPath("$[1].trip.title").value("10월 11일부터 2박 3일 여행"))
                    .andExpect(jsonPath("$[1].trip.startDate").value("2026-10-11"))
                    .andExpect(jsonPath("$[1].trip.endDate").value("2026-10-13"))
                    .andExpect(jsonPath("$[1].trip.regionName").isEmpty())
                    .andExpect(jsonPath("$[1].inviter.userId").value(first.creator().userId()))
                    .andExpect(jsonPath("$[1].dateConflict").value(false))
                    .andExpect(jsonPath("$[1].createdAt").isNotEmpty());

            received(first.creator()).andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("내 여행과 날짜가 겹치면 dateConflict가 true다")
        void received_overlappingTrip_marksDateConflict() throws Exception {
            TripWithFriend trip = tripWithFriend();
            inviteId(trip);
            fixtures.createTrip(trip.friend().accessToken(), defaultStartDate().plusDays(1), 0);

            received(trip.friend()).andExpect(jsonPath("$[0].dateConflict").value(true));
        }

        @Test
        @DisplayName("초대 링크로 먼저 참여하면 대기 중인 친구 초대는 ACCEPTED가 되고 목록에서 사라진다")
        void received_joinedByLink_removesPendingInvite() throws Exception {
            TripWithFriend trip = tripWithFriend();
            inviteId(trip);

            fixtures.joinByInvite(trip.creator().accessToken(), trip.tripId(), trip.friend().accessToken());

            received(trip.friend()).andExpect(jsonPath("$", hasSize(0)));
            sent(trip.creator(), trip.tripId()).andExpect(jsonPath("$[0].status").value("ACCEPTED"));
        }
    }

    @Nested
    @DisplayName("4-8 받은 초대 수락·거절")
    class AcceptDecline {

        @Test
        @DisplayName("수락하면 201과 참여한 여행의 TripContext를 주고, 참여자가 되며 받은 초대함에서 사라진다")
        void accept_pendingInvite_joinsTrip() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);

            acceptInvite(trip.friend(), inviteId)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(trip.tripId()))
                    .andExpect(jsonPath("$.startDate").value("2026-10-11"))
                    .andExpect(jsonPath("$.version").isNumber());

            perform(get("/api/trips/{tripId}/participants", trip.tripId()), trip.friend())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
            received(trip.friend()).andExpect(jsonPath("$", hasSize(0)));
            sent(trip.creator(), trip.tripId()).andExpect(jsonPath("$[0].status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("거절하면 204이고 받은 초대함에서 사라지며 보낸 목록에는 DECLINED로 남는다")
        void decline_pendingInvite_returnsNoContent() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);

            declineInvite(trip.friend(), inviteId).andExpect(status().isNoContent());

            received(trip.friend()).andExpect(jsonPath("$", hasSize(0)));
            sent(trip.creator(), trip.tripId()).andExpect(jsonPath("$[0].status").value("DECLINED"));
        }

        @Test
        @DisplayName("없거나 나에게 온 초대가 아니면 수락·거절 모두 404 INVITE_NOT_FOUND다")
        void acceptDecline_notMine_returnsInviteNotFound() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);
            Member stranger = fixtures.onboardedMember("모르는사람");

            acceptInvite(stranger, inviteId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
            declineInvite(stranger, inviteId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
            acceptInvite(trip.friend(), 999_999L)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
        }

        @Test
        @DisplayName("거절·취소·수락된 초대를 다시 처리하면 409 INVITE_ALREADY_HANDLED다")
        void acceptDecline_handledInvite_returnsAlreadyHandled() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long declined = inviteId(trip);
            declineInvite(trip.friend(), declined).andExpect(status().isNoContent());
            acceptInvite(trip.friend(), declined)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITE_ALREADY_HANDLED"));

            Long cancelled = inviteId(trip);
            cancel(trip.creator(), trip.tripId(), cancelled).andExpect(status().isNoContent());
            acceptInvite(trip.friend(), cancelled)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITE_ALREADY_HANDLED"));
            declineInvite(trip.friend(), cancelled)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITE_ALREADY_HANDLED"));

            Long accepted = inviteId(trip);
            acceptInvite(trip.friend(), accepted).andExpect(status().isCreated());
            acceptInvite(trip.friend(), accepted)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITE_ALREADY_HANDLED"));
        }

        @Test
        @DisplayName("설문을 마치지 않은 친구가 수락하면 409 ONBOARDING_REQUIRED다")
        void accept_withoutOnboarding_returnsOnboardingRequired() throws Exception {
            Member creator = fixtures.onboardedMember("만든사람");
            Member friend = fixtures.signup("설문전친구");
            fixtures.makeFriends(creator, friend);
            Long tripId = fixtures.createTrip(creator.accessToken(), defaultStartDate(), 2);
            Long inviteId = fixtures.friendInviteId(creator.accessToken(), tripId, friend.userId());

            acceptInvite(friend, inviteId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
        }

        @Test
        @DisplayName("내 여행과 날짜가 겹치면 409 TRIP_DATE_OVERLAP이고 초대는 대기 중으로 남는다")
        void accept_overlappingTrip_returnsDateOverlap() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);
            fixtures.createTrip(trip.friend().accessToken(), defaultStartDate().plusDays(2), 1);

            acceptInvite(trip.friend(), inviteId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"))
                    .andExpect(jsonPath("$.details.conflicts", hasSize(1)));
            received(trip.friend()).andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("참여자가 8명인 여행은 초대는 보낼 수 있지만 수락이 409 TRIP_FULL이다")
        void accept_fullTrip_returnsTripFull() throws Exception {
            TripWithFriend trip = tripWithFriend();
            for (int i = 1; i <= 7; i++) {
                Member member = fixtures.onboardedMember("참여자" + i);
                fixtures.joinByInvite(trip.creator().accessToken(), trip.tripId(), member.accessToken());
            }
            Long inviteId = inviteId(trip);

            acceptInvite(trip.friend(), inviteId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_FULL"));
        }

        @Test
        @DisplayName("종료된 여행의 초대는 수락이 409 TRIP_ENDED이고 거절은 204다")
        void acceptDecline_endedTrip_acceptBlockedDeclineAllowed() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);
            clock.setTo(defaultStartDate().plusDays(3));

            acceptInvite(trip.friend(), inviteId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_ENDED"));
            declineInvite(trip.friend(), inviteId).andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("4-14 보낸 친구 초대 목록")
    class Sent {

        @Test
        @DisplayName("누가 보냈든 이 여행의 친구 초대 전체를 최신순으로 참여자 모두에게 같게 준다")
        void sent_listsAllInvitesNewestFirst() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long declined = inviteId(trip);
            declineInvite(trip.friend(), declined).andExpect(status().isNoContent());
            Member other = joinedFriendOf(trip, "다른참여자");
            fixtures.makeFriends(other, trip.friend());
            Long pending = fixtures.friendInviteId(other.accessToken(), trip.tripId(), trip.friend().userId());

            for (Member viewer : List.of(trip.creator(), other)) {
                sent(viewer, trip.tripId())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$", hasSize(2)))
                        .andExpect(jsonPath("$[0].id").value(pending))
                        .andExpect(jsonPath("$[0].status").value("PENDING"))
                        .andExpect(jsonPath("$[0].inviter.nickname").value("다른참여자"))
                        .andExpect(jsonPath("$[0].invitee.userId").value(trip.friend().userId()))
                        .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                        .andExpect(jsonPath("$[1].id").value(declined))
                        .andExpect(jsonPath("$[1].status").value("DECLINED"))
                        .andExpect(jsonPath("$[1].inviter.nickname").value("만든사람"));
            }
        }

        @Test
        @DisplayName("참여하지 않은 여행이면 404 TRIP_NOT_FOUND다")
        void sent_notParticipant_returnsTripNotFound() throws Exception {
            TripWithFriend trip = tripWithFriend();

            sent(trip.friend(), trip.tripId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("4-15 친구 초대 취소")
    class Cancel {

        @Test
        @DisplayName("보낸 사람이 아닌 참여자도 대기 중인 초대를 취소할 수 있고, 받은 초대함에서 사라진다")
        void cancel_byOtherParticipant_removesFromReceived() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);
            Member other = joinedFriendOf(trip, "다른참여자");

            cancel(other, trip.tripId(), inviteId).andExpect(status().isNoContent());

            received(trip.friend()).andExpect(jsonPath("$", hasSize(0)));
            sent(trip.creator(), trip.tripId()).andExpect(jsonPath("$[0].status").value("CANCELLED"));
        }

        @Test
        @DisplayName("참여하지 않은 여행이면 404 TRIP_NOT_FOUND, 다른 여행의 초대거나 없으면 404 INVITE_NOT_FOUND다")
        void cancel_wrongTripOrInvite_returnsNotFound() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);
            Long otherTrip = fixtures.createTrip(trip.creator().accessToken(), defaultStartDate().plusDays(20), 0);

            cancel(trip.friend(), trip.tripId(), inviteId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            cancel(trip.creator(), otherTrip, inviteId)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
            cancel(trip.creator(), trip.tripId(), 999_999L)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
        }

        @Test
        @DisplayName("대기 중이 아닌 초대를 취소하면 409 INVITE_ALREADY_HANDLED다")
        void cancel_handledInvite_returnsAlreadyHandled() throws Exception {
            TripWithFriend trip = tripWithFriend();
            Long inviteId = inviteId(trip);
            declineInvite(trip.friend(), inviteId).andExpect(status().isNoContent());

            cancel(trip.creator(), trip.tripId(), inviteId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITE_ALREADY_HANDLED"));
        }
    }
}
