package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.support.ApiFixtures;
import com.yeso.backend.support.IntegrationTest;
import com.yeso.backend.trip.domain.TripParticipant;
import com.yeso.backend.trip.domain.TripParticipantType;
import com.yeso.backend.trip.infrastructure.TripParticipantRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 context·날짜 중복 차단·목록·탈퇴. 2026-09-24 정책: 여행은 만드는 순간 기간을 차지하고,
 * 날짜는 바꿀 수 없으며, 삭제 대신 개인 탈퇴만 있다(마지막 참여자가 나가면 여행 삭제).
 */
class TripIntegrationTest extends IntegrationTest {

    @Autowired
    private TripPlanRepository tripPlanRepository;

    @Autowired
    private TripParticipantRepository tripParticipantRepository;

    private String onboardedToken() throws Exception {
        return fixtures.onboardedMember().accessToken();
    }

    private static String createBody(LocalDate startDate, int nights, String transport) {
        return """
                {"startDate":"%s","nights":%d,"transport":"%s"}
                """.formatted(startDate, nights, transport);
    }

    private int createTripStatus(String token, LocalDate startDate, int nights) throws Exception {
        return fixtures.createTripResult(token, startDate, nights, "WALK").getResponse().getStatus();
    }

    /** 오늘(고정 시계) 기준 n일 뒤. */
    private LocalDate inDays(int days) {
        return clock.today().plusDays(days);
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @Test
        @DisplayName("토큰이 없으면 여행 context API는 모두 401 AUTH_UNAUTHENTICATED다")
        void tripEndpoints_withoutToken_returnUnauthorized() throws Exception {
            String createJson = createBody(inDays(5), 1, "WALK");
            String checkJson = """
                    {"startDate":"%s","nights":1}
                    """.formatted(inDays(5));
            List<MockHttpServletRequestBuilder> requests = List.of(
                    post("/api/trips").contentType(MediaType.APPLICATION_JSON).content(createJson),
                    post("/api/trips/context/check").contentType(MediaType.APPLICATION_JSON).content(checkJson),
                    get("/api/trips"),
                    get("/api/trips/unavailable-dates")
                            .param("from", inDays(0).toString()).param("to", inDays(30).toString()),
                    get("/api/trips/{id}/context", 1),
                    patch("/api/trips/{id}/context", 1).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"transport":"CAR","version":0}
                                    """),
                    get("/api/trips/{id}/participants", 1),
                    delete("/api/trips/{id}/participants/me", 1));

            for (MockHttpServletRequestBuilder request : requests) {
                mockMvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
            }
        }
    }

    @Nested
    @DisplayName("여행 생성")
    class Create {

        @Test
        @DisplayName("유효한 요청이면 201과 함께 지역·코스 없는 여행을 만들고 만든 사람을 참여자로 등록한다")
        void create_success() throws Exception {
            String token = onboardedToken();
            LocalDate startDate = inDays(10);

            MvcResult result = mockMvc.perform(post("/api/trips")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(startDate, 2, "CAR")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").doesNotExist())
                    .andExpect(jsonPath("$.draftInvalidated").doesNotExist())
                    .andExpect(jsonPath("$.nights").value(2))
                    .andExpect(jsonPath("$.endDate").value(startDate.plusDays(2).toString()))
                    .andExpect(jsonPath("$.transport").value("CAR"))
                    .andExpect(jsonPath("$.regionSigCd").isEmpty())
                    .andExpect(jsonPath("$.regionSelection").isEmpty())
                    .andExpect(jsonPath("$.scheduleDensity").isEmpty())
                    .andExpect(jsonPath("$.hasCourse").value(false))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.dayWindows").doesNotExist())
                    .andReturn();

            Long tripId = Long.valueOf(JsonPath.read(result.getResponse().getContentAsString(), "$.id").toString());
            List<TripParticipant> participants = tripParticipantRepository.findByTripPlanIdOrderByCreatedAtAsc(tripId);
            assertThat(participants).hasSize(1);
            assertThat(participants.get(0).getParticipantType()).isEqualTo(TripParticipantType.OWNER);
        }

        @Test
        @DisplayName("최초 설문을 마치지 않았으면 409 ONBOARDING_REQUIRED다")
        void create_withoutOnboarding_returnsConflict() throws Exception {
            String token = fixtures.signup().accessToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(inDays(5), 1, "WALK")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
        }

        @Test
        @DisplayName("nights=0과 6은 허용된다")
        void create_nightsBoundary_zeroAndSixAccepted() throws Exception {
            String token = onboardedToken();

            assertThat(createTripStatus(token, inDays(20), 0)).isEqualTo(201);
            assertThat(createTripStatus(token, inDays(40), 6)).isEqualTo(201);
        }

        @Test
        @DisplayName("nights=-1 또는 7이면 400 TRIP_INVALID_NIGHTS다")
        void create_nightsOutOfRange_returnsBadRequest() throws Exception {
            String token = onboardedToken();

            for (int nights : new int[]{-1, 7}) {
                mockMvc.perform(post("/api/trips")
                                .header("Authorization", ApiFixtures.bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(inDays(5), nights, "WALK")))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("TRIP_INVALID_NIGHTS"));
            }
        }

        @Test
        @DisplayName("startDate가 오늘이거나 과거면 400 TRIP_INVALID_START_DATE이고 내일이면 허용된다")
        void create_startDateNotInFuture_returnsBadRequest() throws Exception {
            String token = onboardedToken();

            for (LocalDate startDate : new LocalDate[]{clock.today(), inDays(-1)}) {
                mockMvc.perform(post("/api/trips")
                                .header("Authorization", ApiFixtures.bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(startDate, 1, "WALK")))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("TRIP_INVALID_START_DATE"));
            }

            assertThat(createTripStatus(token, inDays(1), 1)).isEqualTo(201);
        }

        @Test
        @DisplayName("transport가 유효하지 않으면 400 TRIP_INVALID_TRANSPORT다")
        void create_invalidTransport_returnsBadRequest() throws Exception {
            String token = onboardedToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(inDays(5), 1, "TELEPORT")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_TRANSPORT"));
        }

        @Test
        @DisplayName("origin은 lat만 있거나 범위를 벗어나면 400 TRIP_INVALID_ORIGIN이다")
        void create_invalidOrigin_returnsBadRequest() throws Exception {
            String token = onboardedToken();

            for (String origin : new String[]{"\"originLat\":37.5", "\"originLat\":999,\"originLng\":37.5"}) {
                mockMvc.perform(post("/api/trips")
                                .header("Authorization", ApiFixtures.bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"%s","nights":1,"transport":"WALK",%s}
                                        """.formatted(inDays(5), origin)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("TRIP_INVALID_ORIGIN"));
            }
        }

        @Test
        @DisplayName("유효한 origin은 그대로 저장된다")
        void create_validOrigin_isPersisted() throws Exception {
            String token = onboardedToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":1,"transport":"WALK","originLat":37.5,"originLng":127.0}
                                    """.formatted(inDays(5))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.originLat").value(37.5))
                    .andExpect(jsonPath("$.originLng").value(127.0));
        }

        @Test
        @DisplayName("이미 만든 여행과 겹치면(포함·시작쪽·끝쪽·감싸기) 409와 conflicts를 반환한다")
        void create_overlapsExistingTrip_returnsConflict() throws Exception {
            String token = onboardedToken();
            LocalDate base = inDays(10);
            fixtures.createTrip(token, base.plusDays(5), 5); // existing: base+5 ~ base+10

            LocalDate[][] requests = {
                    {base.plusDays(6), base.plusDays(7)},   // 포함
                    {base, base.plusDays(5)},               // 시작일에 닿음
                    {base.plusDays(10), base.plusDays(12)}, // 종료일에 닿음
                    {base.plusDays(4), base.plusDays(10)},  // 감싸기
            };
            for (LocalDate[] range : requests) {
                int nights = (int) (range[1].toEpochDay() - range[0].toEpochDay());
                mockMvc.perform(post("/api/trips")
                                .header("Authorization", ApiFixtures.bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(range[0], nights, "WALK")))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"))
                        .andExpect(jsonPath("$.details.conflicts[0].tripId").isNotEmpty());
            }
        }

        @Test
        @DisplayName("다른 사람의 여행과는 겹쳐도 된다")
        void create_otherUsersTrip_doesNotConflict() throws Exception {
            LocalDate base = inDays(10);
            fixtures.createTrip(onboardedToken(), base, 3);

            assertThat(createTripStatus(onboardedToken(), base, 3)).isEqualTo(201);
        }

        @Test
        @DisplayName("같은 사용자가 겹치는 여행을 동시에 만들면 하나만 성공한다")
        void create_concurrentOverlapping_onlyOneSucceeds() throws Exception {
            String token = onboardedToken();
            LocalDate base = inDays(10);

            int threadCount = 3;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger created = new AtomicInteger();
            AtomicInteger conflicted = new AtomicInteger();
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    start.await();
                    int status = createTripStatus(token, base, 2);
                    if (status == 201) {
                        created.incrementAndGet();
                    } else if (status == 409) {
                        conflicted.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(created.get()).isEqualTo(1);
            assertThat(conflicted.get()).isEqualTo(threadCount - 1);
        }
    }

    @Nested
    @DisplayName("날짜 중복 사전 확인")
    class Check {

        @Test
        @DisplayName("겹치지 않으면 available=true다")
        void check_noConflict_returnsAvailableTrue() throws Exception {
            String token = onboardedToken();

            mockMvc.perform(post("/api/trips/context/check")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":2}
                                    """.formatted(inDays(5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.conflicts.length()").value(0));
        }

        @Test
        @DisplayName("이미 만든 여행과 겹치면 available=false와 conflicts를 반환하고 저장하지 않는다")
        void check_conflict_returnsAvailableFalseWithoutSaving() throws Exception {
            String token = onboardedToken();
            LocalDate base = inDays(10);
            fixtures.createTrip(token, base, 3);

            mockMvc.perform(post("/api/trips/context/check")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":1}
                                    """.formatted(base.plusDays(1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.conflicts.length()").value(1))
                    .andExpect(jsonPath("$.conflicts[0].title").value("10월 11일부터 3박 4일 여행"));

            mockMvc.perform(get("/api/trips").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Nested
    @DisplayName("context 조회")
    class Context {

        @Test
        @DisplayName("참여자는 여행 context를 조회할 수 있다")
        void getContext_participant_success() throws Exception {
            String token = onboardedToken();
            Long tripId = fixtures.createTrip(token, inDays(5), 2);

            mockMvc.perform(get("/api/trips/{id}/context", tripId).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(tripId))
                    .andExpect(jsonPath("$.hasCourse").value(false));
        }

        @Test
        @DisplayName("없거나 참여하지 않은 여행은 404 TRIP_NOT_FOUND다")
        void getContext_notParticipant_returnsNotFound() throws Exception {
            Long tripId = fixtures.createTrip(onboardedToken(), inDays(5), 2);
            String otherToken = onboardedToken();

            mockMvc.perform(get("/api/trips/999999999/context").header("Authorization", ApiFixtures.bearer(otherToken)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            mockMvc.perform(get("/api/trips/{id}/context", tripId).header("Authorization", ApiFixtures.bearer(otherToken)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("이동수단·출발지 수정")
    class Update {

        private String patchBody(String transport, int version) {
            return """
                    {"transport":"%s","originLat":37.5,"originLng":127.0,"version":%d}
                    """.formatted(transport, version);
        }

        @Test
        @DisplayName("이동수단·출발지를 바꾸면 200과 증가한 version을 반환하고 날짜는 그대로다")
        void update_success() throws Exception {
            String token = onboardedToken();
            LocalDate startDate = inDays(5);
            Long tripId = fixtures.createTrip(token, startDate, 2);

            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody("CAR", 0)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transport").value("CAR"))
                    .andExpect(jsonPath("$.originLat").value(37.5))
                    .andExpect(jsonPath("$.startDate").value(startDate.toString()))
                    .andExpect(jsonPath("$.nights").value(2))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("출발지를 둘 다 null로 보내면 출발지를 지운다")
        void update_clearOrigin() throws Exception {
            String token = onboardedToken();
            Long tripId = fixtures.createTrip(token, inDays(5), 2);

            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody("CAR", 0)))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"originLat\":null,\"originLng\":null,\"version\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transport").value("CAR"))
                    .andExpect(jsonPath("$.originLat").isEmpty());
        }

        @Test
        @DisplayName("시작일·박수를 보내면 400 COMMON_INVALID_REQUEST다(생성 후 날짜 변경 불가)")
        void update_dates_returnsBadRequest() throws Exception {
            String token = onboardedToken();
            Long tripId = fixtures.createTrip(token, inDays(5), 2);

            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":3,"version":0}
                                    """.formatted(inDays(6))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }

        @Test
        @DisplayName("version이 다르면 409 TRIP_VERSION_CONFLICT다")
        void update_staleVersion_returnsConflict() throws Exception {
            String token = onboardedToken();
            Long tripId = fixtures.createTrip(token, inDays(5), 2);

            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody("CAR", 5)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("참여하지 않은 여행은 404 TRIP_NOT_FOUND다")
        void update_notParticipant_returnsNotFound() throws Exception {
            Long tripId = fixtures.createTrip(onboardedToken(), inDays(5), 2);

            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(onboardedToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody("CAR", 0)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("종료일 당일까지는 수정되고 종료일 다음 날부터는 409 TRIP_ENDED다")
        void update_endedTrip_returnsConflict() throws Exception {
            String token = onboardedToken();
            LocalDate startDate = inDays(5);
            Long tripId = fixtures.createTrip(token, startDate, 1); // 종료일 = startDate + 1

            clock.setTo(startDate.plusDays(1));
            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody("CAR", 0)))
                    .andExpect(status().isOk());

            clock.setTo(startDate.plusDays(2));
            mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                            .header("Authorization", ApiFixtures.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody("PUBLIC_TRANSIT", 1)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_ENDED"));
        }

        @Test
        @DisplayName("동시에 같은 version으로 두 번 수정하면 정확히 하나만 성공한다")
        void update_concurrentSameVersion_onlyOneSucceeds() throws Exception {
            String token = onboardedToken();
            Long tripId = fixtures.createTrip(token, inDays(5), 2);

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    start.await();
                    int status = mockMvc.perform(patch("/api/trips/{id}/context", tripId)
                                    .header("Authorization", ApiFixtures.bearer(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(patchBody("CAR", 0)))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(threadCount - 1);
        }
    }

    @Nested
    @DisplayName("선택 불가능한 날짜")
    class UnavailableDates {

        @Test
        @DisplayName("내 여행 기간을 반환하고 다른 사람의 여행은 포함하지 않는다")
        void unavailableDates_returnsMyTrips() throws Exception {
            String token = onboardedToken();
            LocalDate base = inDays(10);
            Long tripId = fixtures.createTrip(token, base, 3);
            fixtures.createTrip(onboardedToken(), base.plusDays(1), 1);

            mockMvc.perform(get("/api/trips/unavailable-dates")
                            .header("Authorization", ApiFixtures.bearer(token))
                            .param("from", base.minusDays(5).toString())
                            .param("to", base.plusDays(10).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].tripId").value(tripId))
                    .andExpect(jsonPath("$[0].startDate").value(base.toString()))
                    .andExpect(jsonPath("$[0].endDate").value(base.plusDays(3).toString()));
        }
    }

    @Nested
    @DisplayName("내 여행 목록·참여자·탈퇴")
    class ListAndLeave {

        @Test
        @DisplayName("내 여행 목록은 시작일 순이고 참여자를 포함한다")
        void list_returnsMyTripsInStartDateOrder() throws Exception {
            String token = onboardedToken();
            Long later = fixtures.createTrip(token, inDays(60), 1);
            Long sooner = fixtures.createTrip(token, inDays(30), 1);

            mockMvc.perform(get("/api/trips").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].tripId").value(sooner))
                    .andExpect(jsonPath("$[1].tripId").value(later))
                    .andExpect(jsonPath("$[0].participants.length()").value(1))
                    .andExpect(jsonPath("$[0].hasCourse").value(false))
                    .andExpect(jsonPath("$[0].myDiaryId").isEmpty())
                    .andExpect(jsonPath("$[0].title").value("10월 31일부터 1박 2일 여행"));

            mockMvc.perform(get("/api/trips").param("period", "PAST").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("코스 제목이 없는 당일치기 여행은 'M월 D일 당일 여행' 제목을 받는다")
        void list_dayTripWithoutCourse_hasFallbackTitle() throws Exception {
            String token = onboardedToken();
            fixtures.createTrip(token, inDays(3), 0);

            mockMvc.perform(get("/api/trips").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(jsonPath("$[0].title").value("10월 4일 당일 여행"));
        }

        @Test
        @DisplayName("종료된 여행은 PAST에, 다가오는 여행은 UPCOMING에 나오고 탈퇴는 여전히 된다")
        void list_periodAndLeaveEndedTrip() throws Exception {
            String token = onboardedToken();
            Long ended = fixtures.createTrip(token, inDays(5), 1);
            Long upcoming = fixtures.createTrip(token, inDays(30), 1);
            clock.setTo(inDays(7)); // 첫 여행(5~6일 뒤)의 종료일이 지났다

            mockMvc.perform(get("/api/trips").param("period", "PAST").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].tripId").value(ended));
            mockMvc.perform(get("/api/trips").param("period", "UPCOMING").header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].tripId").value(upcoming));
            mockMvc.perform(delete("/api/trips/{id}/participants/me", ended).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("참여자 목록은 만든 사람을 isCreator=true로 표시한다")
        void participants_listsCreator() throws Exception {
            String token = onboardedToken();
            Long tripId = fixtures.createTrip(token, inDays(30), 1);

            mockMvc.perform(get("/api/trips/{id}/participants", tripId).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].nickname").value("tester"))
                    .andExpect(jsonPath("$[0].isCreator").value(true));
        }

        @Test
        @DisplayName("마지막 참여자가 탈퇴하면 204이고 여행이 삭제되어 그 기간을 다시 쓸 수 있다")
        void leave_lastParticipant_deletesTrip() throws Exception {
            String token = onboardedToken();
            LocalDate base = inDays(10);
            Long tripId = fixtures.createTrip(token, base, 2);

            mockMvc.perform(delete("/api/trips/{id}/participants/me", tripId).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isNoContent());

            assertThat(tripPlanRepository.findById(tripId)).isEmpty();
            mockMvc.perform(get("/api/trips/{id}/context", tripId).header("Authorization", ApiFixtures.bearer(token)))
                    .andExpect(status().isNotFound());
            assertThat(createTripStatus(token, base, 2)).isEqualTo(201);
        }

        @Test
        @DisplayName("참여하지 않은 여행에서 탈퇴하면 404 TRIP_NOT_FOUND다")
        void leave_notParticipant_returnsNotFound() throws Exception {
            Long tripId = fixtures.createTrip(onboardedToken(), inDays(30), 1);

            mockMvc.perform(delete("/api/trips/{id}/participants/me", tripId)
                            .header("Authorization", ApiFixtures.bearer(onboardedToken())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
            assertThat(tripPlanRepository.findById(tripId)).isPresent();
        }
    }
}
