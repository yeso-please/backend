package com.yeso.backend.trip;

import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 참여자 내보내기(D7·3-10), 접속 표시(D12·3-11·3-12), 초대 미리보기·둘러보기(D8·D9·4-4·4-7·4-16). */
class TripCollaborationIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Long trip(Member owner) throws Exception {
        return fixtures.createTrip(owner.accessToken(), clock.today().plusDays(10), 1);
    }

    @Nested
    @DisplayName("3-10 참여자 내보내기")
    class Kick {

        @Test
        @DisplayName("생성자가 내보내면 그 사람은 여행에 접근할 수 없고 3-8에서 빠진다")
        void creatorKicks() throws Exception {
            Member owner = fixtures.onboardedMember("생성자");
            Member friend = fixtures.onboardedMember("민지");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());

            mockMvc.perform(get("/api/trips/{tripId}/participants", tripId).header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$[0].isManager").value(true))
                    .andExpect(jsonPath("$[1].isManager").value(false));

            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, friend.userId())
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/trips/{tripId}/context", tripId).header("Authorization", friend.bearer()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/trips/{tripId}/participants", tripId).header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("관리자가 아니면 403 TRIP_MANAGER_ONLY, 자기 자신은 400, 참여자가 아니면 404 TRIP_PARTICIPANT_NOT_FOUND")
        void rules() throws Exception {
            Member owner = fixtures.onboardedMember("생성자");
            Member friend = fixtures.onboardedMember("민지");
            Member stranger = fixtures.onboardedMember("남");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());

            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, owner.userId())
                            .header("Authorization", friend.bearer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TRIP_MANAGER_ONLY"));
            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, owner.userId())
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, stranger.userId())
                            .header("Authorization", owner.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_PARTICIPANT_NOT_FOUND"));
            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, friend.userId())
                            .header("Authorization", stranger.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("생성자가 나가면 가장 먼저 들어온 참여자가 관리자가 된다(Q7)")
        void managerSuccession() throws Exception {
            Member owner = fixtures.onboardedMember("생성자");
            Member first = fixtures.onboardedMember("먼저");
            Member second = fixtures.onboardedMember("나중");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, first.accessToken());
            clock.advance(Duration.ofMinutes(1));
            fixtures.joinByInvite(owner.accessToken(), tripId, second.accessToken());
            mockMvc.perform(delete("/api/trips/{tripId}/participants/me", tripId).header("Authorization", owner.bearer()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, first.userId())
                            .header("Authorization", second.bearer()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/trips/{tripId}/participants/{userId}", tripId, second.userId())
                            .header("Authorization", first.bearer()))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("3-11·3-12 접속 표시")
    class Presence {

        @Test
        @DisplayName("신호를 보낸 참여자만 60초 동안 보인다. 참여자가 아니면 404다")
        void heartbeat() throws Exception {
            Member owner = fixtures.onboardedMember("수정");
            Member friend = fixtures.onboardedMember("민지");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());

            mockMvc.perform(post("/api/trips/{tripId}/presence", tripId).header("Authorization", friend.bearer()))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/trips/{tripId}/presence", tripId).header("Authorization", owner.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.heartbeatSeconds").value(20))
                    .andExpect(jsonPath("$.participants[*].nickname", contains("민지")));

            clock.advance(Duration.ofSeconds(61));
            mockMvc.perform(get("/api/trips/{tripId}/presence", tripId).header("Authorization", owner.bearer()))
                    .andExpect(jsonPath("$.participants.length()").value(0));

            Member stranger = fixtures.onboardedMember("남");
            mockMvc.perform(post("/api/trips/{tripId}/presence", tripId).header("Authorization", stranger.bearer()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("초대 미리보기·둘러보기")
    class InvitePreview {

        @Test
        @DisplayName("4-4는 참여자 닉네임과 지역 대표 사진을 보여 준다")
        void publicSummary() throws Exception {
            Member owner = fixtures.onboardedMember("수정");
            Member friend = fixtures.onboardedMember("민지");
            Long tripId = trip(owner);
            fixtures.joinByInvite(owner.accessToken(), tripId, friend.accessToken());
            Long attraction = regionWithAttraction(tripId);

            String token = fixtures.inviteToken(owner.accessToken(), tripId);
            mockMvc.perform(get("/api/invites/{token}", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participantCount").value(2))
                    .andExpect(jsonPath("$.participants[*].nickname", containsInAnyOrder("수정", "민지")))
                    .andExpect(jsonPath("$.heroImageUrl").value("https://image.example/" + attraction))
                    .andExpect(jsonPath("$.regionSigCd").value("51150"))
                    .andExpect(jsonPath("$.hasCourse").value(false));
        }

        @Test
        @DisplayName("4-7 받은 초대에도 참여자와 대표 사진이 있다")
        void receivedInvites() throws Exception {
            Member owner = fixtures.onboardedMember("수정");
            Member friend = fixtures.onboardedMember("민지");
            fixtures.makeFriends(owner, friend);
            Long tripId = trip(owner);
            regionWithAttraction(tripId);
            fixtures.friendInviteId(owner.accessToken(), tripId, friend.userId());

            mockMvc.perform(get("/api/me/trip-invites").header("Authorization", friend.bearer()))
                    .andExpect(jsonPath("$[0].trip.participants[*].nickname", contains("수정")))
                    .andExpect(jsonPath("$[0].trip.heroImageUrl").isNotEmpty());
        }

        @Test
        @DisplayName("4-16 코스가 없으면 days가 비어 있고, 만료된 초대는 410이다")
        void courseView() throws Exception {
            Member owner = fixtures.onboardedMember("수정");
            Long tripId = trip(owner);
            String token = fixtures.inviteToken(owner.accessToken(), tripId);
            mockMvc.perform(get("/api/invites/{token}/course", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.myRole").value("VIEWER"))
                    .andExpect(jsonPath("$.days.length()").value(0));

            clock.advance(Duration.ofDays(8));
            mockMvc.perform(get("/api/invites/{token}/course", token))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        }
    }

    private Long regionWithAttraction(Long tripId) {
        jdbc.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('51150', '강원특별자치도', '강릉시', 37.75, 128.9)");
        jdbc.update("update app.trip_plans set region_id = '51150' where id = ?", tripId);
        Long id = jdbc.queryForObject("""
                insert into app.attractions (name, category, region_id, description, lat, lng, content_type_id, source_content_id)
                values ('경포해변', '관광지', '51150', '해변', 37.8, 128.9, 12, ?) returning id
                """, Long.class, UUID.randomUUID().toString());
        jdbc.update("insert into app.attraction_images (attraction_id, image_url, validation_status) values (?, ?, 'VALID')",
                id, "https://image.example/" + id);
        return id;
    }
}
