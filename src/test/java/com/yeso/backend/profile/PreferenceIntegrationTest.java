package com.yeso.backend.profile;

import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회원 설정(docs/api/profile.md 2-11·2-12). */
class PreferenceIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("토큰 없이 부르면 401 AUTH_UNAUTHENTICATED다")
    void withoutToken() throws Exception {
        mockMvc.perform(get("/api/me/preferences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mockMvc.perform(patch("/api/me/preferences").contentType(MediaType.APPLICATION_JSON).content("{\"courseTasteMode\":\"RANDOM\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("처음에는 취향 반영(TASTE)이고, 완전 랜덤으로 바꾸면 그대로 남는다")
    void defaultThenChange() throws Exception {
        Member member = fixtures.signup();

        mockMvc.perform(get("/api/me/preferences").header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseTasteMode").value("TASTE"));

        mockMvc.perform(patch("/api/me/preferences")
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseTasteMode\":\"RANDOM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseTasteMode").value("RANDOM"));
        mockMvc.perform(patch("/api/me/preferences")
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseTasteMode\":\"TASTE\"}"))
                .andExpect(jsonPath("$.courseTasteMode").value("TASTE"));
        mockMvc.perform(get("/api/me/preferences").header("Authorization", member.bearer()))
                .andExpect(jsonPath("$.courseTasteMode").value("TASTE"));
    }

    @Test
    @DisplayName("TASTE·RANDOM이 아니거나 빠지면 400 COMMON_INVALID_REQUEST와 필드 오류다")
    void invalidValue() throws Exception {
        Member member = fixtures.signup();

        for (String body : new String[]{"{\"courseTasteMode\":\"SOMETIMES\"}", "{}"}) {
            mockMvc.perform(patch("/api/me/preferences")
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("courseTasteMode"));
        }
    }
}
