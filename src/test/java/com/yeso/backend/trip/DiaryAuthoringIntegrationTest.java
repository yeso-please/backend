package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiaryAuthoringIntegrationTest extends IntegrationTest {

    @Nested
    @DisplayName("여행기 초안")
    class Create {

        @Test
        @DisplayName("여행 종료 후 참여자는 코스 제목을 기본값으로 여행기를 만든다")
        void create_afterTripEnded_returnsDraft() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long tripId = endedTrip(member.accessToken());

            mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.diaryId").isNotEmpty())
                    .andExpect(jsonPath("$.tripId").value(tripId))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                    .andExpect(jsonPath("$.locationPrecision").value("CITY"));
        }

        @Test
        @DisplayName("여행이 끝나지 않았으면 409 TRIP_NOT_ENDED다")
        void create_beforeTripEnded_returnsConflict() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(member.accessToken(), clock.today().plusDays(1), 0);

            mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_ENDED"));
        }

        @Test
        @DisplayName("여행기 초안이 이미 있으면 중복 생성을 막는다")
        void create_twice_returnsConflict() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long tripId = endedTrip(member.accessToken());
            mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                    .header("Authorization", member.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"));

            mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DIARY_ALREADY_EXISTS_FOR_TRIP"))
                    .andExpect(jsonPath("$.details.diaryId").isNotEmpty());
        }

        @Test
        @DisplayName("토큰이 없으면 여행기를 만들 수 없다")
        void create_withoutToken_returnsUnauthorized() throws Exception {
            mockMvc.perform(post("/api/courses/{tripId}/diary", 1)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("여행 참여자가 아니면 여행기 생성에서 존재를 숨긴다")
        void create_asNonParticipant_returnsNotFound() throws Exception {
            ApiFixtures.Member owner = fixtures.onboardedMember("owner");
            Long tripId = endedTrip(owner.accessToken());
            ApiFixtures.Member stranger = fixtures.onboardedMember("stranger");

            mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                            .header("Authorization", stranger.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("사진 업로드·발행·삭제")
    class Photos {

        @Test
        @DisplayName("사진은 검증·재인코딩·썸네일 생성 후 private URL로 응답한다")
        void upload_validPng_returnsPrivatePhotoUrls() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);
            MockMultipartFile file = png("files", "trip.png");

            MvcResult result = mockMvc.perform(multipart("/api/diaries/{diaryId}/photos", diaryId)
                            .file(file)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.photos.length()").value(1))
                    .andExpect(jsonPath("$.photos[0].photoId").isNotEmpty())
                    .andExpect(jsonPath("$.photos[0].url").value(org.hamcrest.Matchers.startsWith("https://private.test/")))
                    .andExpect(jsonPath("$.photos[0].thumbnailUrl").value(org.hamcrest.Matchers.containsString("-thumb.jpg")))
                    .andReturn();

            assertThat(fakeDiaryPhotoStorage.size()).isEqualTo(2);
            Number order = JsonPath.read(result.getResponse().getContentAsString(), "$.photos[0].order");
            assertThat(order.intValue()).isZero();
        }

        @Test
        @DisplayName("이미지 내용과 MIME이 다르면 파일 인덱스를 포함해 400이다")
        void upload_mimeDoesNotMatch_returnsInvalidPhoto() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);
            MockMultipartFile file = new MockMultipartFile("files", "fake.jpg", "image/jpeg", new byte[]{1, 2, 3});

            mockMvc.perform(multipart("/api/diaries/{diaryId}/photos", diaryId)
                            .file(file).header("Authorization", member.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DIARY_PHOTO_INVALID"))
                    .andExpect(jsonPath("$.details.fileIndex").value(0));
            assertThat(fakeDiaryPhotoStorage.size()).isZero();
        }

        @Test
        @DisplayName("여러 파일 중 하나가 잘못되면 사진을 하나도 저장하지 않는다")
        void upload_oneInvalidFile_rollsBackWholeBatch() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);

            var request = multipart("/api/diaries/{diaryId}/photos", diaryId)
                    .file(png("files", "valid.png"))
                    .file(new MockMultipartFile("files", "invalid.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                    .header("Authorization", member.bearer());
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DIARY_PHOTO_INVALID"))
                    .andExpect(jsonPath("$.details.fileIndex").value(1));
            assertThat(fakeDiaryPhotoStorage.size()).isZero();
        }

        @Test
        @DisplayName("30장을 넘는 업로드는 저장소를 쓰기 전에 거부한다")
        void upload_moreThanThirtyFiles_returnsLimitError() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);
            var request = multipart("/api/diaries/{diaryId}/photos", diaryId)
                    .header("Authorization", member.bearer());
            for (int i = 0; i < 31; i++) request.file(png("files", "photo-%d.png".formatted(i)));

            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DIARY_PHOTO_LIMIT_EXCEEDED"));
            assertThat(fakeDiaryPhotoStorage.size()).isZero();
        }

        @Test
        @DisplayName("사진이 없는 여행기는 발행할 수 없다")
        void publish_withoutPhoto_returnsUnprocessable() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);

            mockMvc.perform(post("/api/diaries/{diaryId}/publish", diaryId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DIARY_NOT_PUBLISHABLE"))
                    .andExpect(jsonPath("$.details.missing[0]").value("PHOTO"));
        }

        @Test
        @DisplayName("여행기 본문·공개 범위·위치 정밀도·취향 반영 설정을 수정한다")
        void update_validFields_returnsUpdatedDiary() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);

            mockMvc.perform(patch("/api/diaries/{diaryId}", diaryId)
                            .header("Authorization", member.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"바닷길 산책","body":"기억에 남는 하루","visibility":"FRIENDS",
                                     "locationPrecision":"HIDDEN","satisfaction":5,"experienceTags":["바다","산책"],
                                     "includeInTasteProfile":false}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("바닷길 산책"))
                    .andExpect(jsonPath("$.body").value("기억에 남는 하루"))
                    .andExpect(jsonPath("$.visibility").value("FRIENDS"))
                    .andExpect(jsonPath("$.locationPrecision").value("HIDDEN"))
                    .andExpect(jsonPath("$.experienceTags[0]").value("바다"))
                    .andExpect(jsonPath("$.includeInTasteProfile").value(false));
        }

        @Test
        @DisplayName("발행된 여행기의 마지막 사진은 삭제할 수 없다")
        void deleteLastPhoto_fromPublishedDiary_returnsUnprocessable() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);
            MvcResult upload = mockMvc.perform(multipart("/api/diaries/{diaryId}/photos", diaryId)
                    .file(png("files", "trip.png")).header("Authorization", member.bearer()))
                    .andExpect(status().isCreated()).andReturn();
            Long photoId = ((Number) JsonPath.read(upload.getResponse().getContentAsString(), "$.photos[0].photoId"))
                    .longValue();
            mockMvc.perform(post("/api/diaries/{diaryId}/publish", diaryId)
                    .header("Authorization", member.bearer())).andExpect(status().isOk());

            mockMvc.perform(delete("/api/diaries/{diaryId}/photos/{photoId}", diaryId, photoId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("DIARY_NOT_PUBLISHABLE"));
            assertThat(fakeDiaryPhotoStorage.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("사진이 있는 초안은 발행되고 사진 삭제 후 여행기 삭제가 객체도 지운다")
        void publishAndDelete_removesStoredObjects() throws Exception {
            ApiFixtures.Member member = fixtures.onboardedMember();
            Long diaryId = createDiary(member);
            mockMvc.perform(multipart("/api/diaries/{diaryId}/photos", diaryId)
                    .file(png("files", "trip.png")).header("Authorization", member.bearer()))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/diaries/{diaryId}/publish", diaryId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.publishedAt").isNotEmpty());

            mockMvc.perform(delete("/api/diaries/{diaryId}", diaryId)
                            .header("Authorization", member.bearer()))
                    .andExpect(status().isNoContent());
            assertThat(fakeDiaryPhotoStorage.size()).isZero();
        }

        @Test
        @DisplayName("작성자가 아니면 여행기를 삭제할 수 없고 존재를 숨긴다")
        void delete_asAnotherUser_returnsNotFound() throws Exception {
            ApiFixtures.Member author = fixtures.onboardedMember("author");
            Long diaryId = createDiary(author);
            ApiFixtures.Member stranger = fixtures.onboardedMember("stranger");

            mockMvc.perform(delete("/api/diaries/{diaryId}", diaryId)
                            .header("Authorization", stranger.bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DIARY_NOT_FOUND"));
        }
    }

    private Long endedTrip(String token) throws Exception {
        Long tripId = fixtures.createTrip(token, clock.today().plusDays(1), 0);
        clock.advance(Duration.ofDays(2));
        return tripId;
    }

    private Long createDiary(ApiFixtures.Member member) throws Exception {
        Long tripId = endedTrip(member.accessToken());
        MvcResult result = mockMvc.perform(post("/api/courses/{tripId}/diary", tripId)
                        .header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.diaryId")).longValue();
    }

    private static MockMultipartFile png(String partName, String filename) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile(partName, filename, "image/png", output.toByteArray());
    }
}
