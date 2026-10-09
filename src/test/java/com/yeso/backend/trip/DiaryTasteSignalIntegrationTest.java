package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.attraction.domain.AttractionCategory;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import com.yeso.backend.trip.application.course.TasteEvidence;
import com.yeso.backend.trip.application.course.TasteEvidenceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 여행기 취향 신호가 작성자 본인의 추천 근거에 들어가고, 끄거나 지우면 빠진다(docs/api/trip.md 여행기 취향 신호). */
class DiaryTasteSignalIntegrationTest extends IntegrationTest {

    @Autowired
    private TasteEvidenceFactory tasteEvidenceFactory;

    private Long publishedDiary(ApiFixtures.Member member, int satisfaction, String tagsJson) throws Exception {
        Long tripId = fixtures.createTrip(member.accessToken(), clock.today().plusDays(1), 0);
        clock.advance(Duration.ofDays(2));
        MvcResult created = mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        Long diaryId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.diaryId")).longValue();
        mockMvc.perform(patch("/api/diaries/{diaryId}", diaryId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"satisfaction\":%d,\"experienceTags\":%s,\"includeInTasteProfile\":true}"
                                .formatted(satisfaction, tagsJson)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/diaries/{diaryId}/publish", diaryId).header("Authorization", member.bearer()))
                .andExpect(status().isOk());
        return diaryId;
    }

    @Test
    @DisplayName("발행한 여행기의 태그가 유형 친화도와 추천 이유가 되고, 설정을 끄면 빠진다")
    void publishedSignal_isUsed_andRemovedWhenOptedOut() throws Exception {
        ApiFixtures.Member member = fixtures.onboardedMember();
        Long diaryId = publishedDiary(member, 5, "[\"산책\",\"바다\",\"시장\"]");

        TasteEvidence evidence = tasteEvidenceFactory.forUser(member.userId());
        assertThat(evidence.affinity(AttractionCategory.WALK_REST)).isEqualTo(1.0);
        assertThat(evidence.affinity(AttractionCategory.NATURE)).isEqualTo(1.0);
        assertThat(evidence.affinity(AttractionCategory.HISTORY_CULTURE)).isZero();
        assertThat(evidence.reasonsFor(AttractionCategory.WALK_REST)).containsExactly("지난 여행에서 좋았던 '산책'과 비슷해요");
        assertThat(evidence.reasonsFor(AttractionCategory.NATURE)).containsExactly("지난 여행에서 좋았던 '바다'와 비슷해요");

        mockMvc.perform(patch("/api/diaries/{diaryId}", diaryId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"includeInTasteProfile\":false}"))
                .andExpect(status().isOk());

        TasteEvidence optedOut = tasteEvidenceFactory.forUser(member.userId());
        assertThat(optedOut.affinity(AttractionCategory.WALK_REST)).isZero();
        assertThat(optedOut.reasonsFor(AttractionCategory.WALK_REST)).isEmpty();
    }

    @Test
    @DisplayName("여행기를 지우면 신호가 빠지고, 다른 회원의 여행기는 내 근거에 섞이지 않는다")
    void deletedSignal_isRemoved_andOthersAreIgnored() throws Exception {
        ApiFixtures.Member member = fixtures.onboardedMember();
        ApiFixtures.Member other = fixtures.onboardedMember();
        Long diaryId = publishedDiary(member, 4, "[\"체험\"]");
        publishedDiary(other, 5, "[\"역사\"]");

        assertThat(tasteEvidenceFactory.forUser(member.userId()).affinity(AttractionCategory.ACTIVITY)).isEqualTo(1.0);
        assertThat(tasteEvidenceFactory.forUser(member.userId()).affinity(AttractionCategory.HISTORY_CULTURE)).isZero();

        mockMvc.perform(delete("/api/diaries/{diaryId}", diaryId).header("Authorization", member.bearer()))
                .andExpect(status().isNoContent());

        assertThat(tasteEvidenceFactory.forUser(member.userId()).affinity(AttractionCategory.ACTIVITY)).isZero();
    }

    @Test
    @DisplayName("만족도 1~2인 여행기는 쓰지 않는다(감점하지 않는다)")
    void lowSatisfaction_isIgnored() throws Exception {
        ApiFixtures.Member member = fixtures.onboardedMember();
        publishedDiary(member, 2, "[\"자연\"]");

        TasteEvidence evidence = tasteEvidenceFactory.forUser(member.userId());
        assertThat(evidence.affinity(AttractionCategory.NATURE)).isZero();
        assertThat(evidence.reasonsFor(AttractionCategory.NATURE)).isEmpty();
    }
}
