package com.yeso.backend.trip;

import com.jayway.jsonpath.JsonPath;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.domain.TripPlanStatus;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=test-only-secret-not-used-outside-automated-tests",
        "spring.jpa.properties.hibernate.default_schema=app"
})
class TripIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("tripin_trip_test")
            .withUsername("tripin_test")
            .withPassword("tripin_test");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripPlanRepository tripPlanRepository;

    private static int emailSeq = 0;

    private String accessTokenFor(String email) throws Exception {
        MvcResult signup = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","nickname":"tester"}
                                """.formatted(email)))
                .andReturn();
        return JsonPath.read(signup.getResponse().getContentAsString(), "$.accessToken");
    }

    private String freshToken() throws Exception {
        return accessTokenFor("trip" + (emailSeq++) + "_" + System.nanoTime() + "@example.com");
    }

    private static String createBody(LocalDate startDate, int nights, String transport) {
        return """
                {"startDate":"%s","nights":%d,"transport":"%s"}
                """.formatted(startDate, nights, transport);
    }

    private MvcResult createTrip(String token, LocalDate startDate, int nights) throws Exception {
        return mockMvc.perform(post("/api/trips")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(startDate, nights, "WALK")))
                .andReturn();
    }

    private Long createConfirmedTrip(String token, LocalDate startDate, int nights) throws Exception {
        MvcResult result = createTrip(token, startDate, nights);
        Long tripId = Long.valueOf(JsonPath.read(result.getResponse().getContentAsString(), "$.id").toString());
        TripPlan tripPlan = tripPlanRepository.findById(tripId).orElseThrow();
        tripPlan.setStatus(TripPlanStatus.CONFIRMED);
        tripPlanRepository.save(tripPlan);
        return tripId;
    }

    @Nested
    @DisplayName("여행 생성")
    class Create {

        @Test
        @DisplayName("유효한 요청이면 201과 함께 DRAFT 여행을 만든다")
        void create_success() throws Exception {
            String token = freshToken();
            LocalDate startDate = LocalDate.now().plusDays(10);

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(startDate, 2, "CAR")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.nights").value(2))
                    .andExpect(jsonPath("$.endDate").value(startDate.plusDays(2).toString()))
                    .andExpect(jsonPath("$.transport").value("CAR"))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.dayWindows.length()").value(3));
        }

        @Test
        @DisplayName("nights=0과 6은 허용된다")
        void create_nightsBoundary_zeroAndSixAccepted() throws Exception {
            String token = freshToken();

            createTrip(token, LocalDate.now().plusDays(5), 0).getResponse();
            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now().plusDays(20), 0, "WALK")))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now().plusDays(40), 6, "WALK")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("nights=-1 또는 7이면 400 INVALID_NIGHTS를 반환한다")
        void create_nightsOutOfRange_returnsBadRequest() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now().plusDays(5), -1, "WALK")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_NIGHTS"));

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now().plusDays(5), 7, "WALK")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_NIGHTS"));
        }

        @Test
        @DisplayName("startDate가 오늘이거나 과거면 400 INVALID_START_DATE를 반환한다")
        void create_startDateNotInFuture_returnsBadRequest() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now(), 1, "WALK")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_START_DATE"));

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now().minusDays(1), 1, "WALK")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_START_DATE"));
        }

        @Test
        @DisplayName("transport가 유효하지 않으면 400 INVALID_TRANSPORT를 반환한다")
        void create_invalidTransport_returnsBadRequest() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(LocalDate.now().plusDays(5), 1, "TELEPORT")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_TRANSPORT"));
        }

        @Test
        @DisplayName("origin은 lat만 있으면 400 INVALID_ORIGIN을 반환한다(XOR)")
        void create_originOnlyLat_returnsBadRequest() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":1,"transport":"WALK","originLat":37.5}
                                    """.formatted(LocalDate.now().plusDays(5))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_ORIGIN"));
        }

        @Test
        @DisplayName("origin 범위를 벗어나면 400 INVALID_ORIGIN을 반환한다")
        void create_originOutOfRange_returnsBadRequest() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":1,"transport":"WALK","originLat":999,"originLng":37.5}
                                    """.formatted(LocalDate.now().plusDays(5))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TRIP_INVALID_ORIGIN"));
        }

        @Test
        @DisplayName("유효한 origin은 그대로 저장된다")
        void create_validOrigin_isPersisted() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":1,"transport":"WALK","originLat":37.5,"originLng":127.0}
                                    """.formatted(LocalDate.now().plusDays(5))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.originLat").value(37.5))
                    .andExpect(jsonPath("$.originLng").value(127.0));
        }

        @Test
        @DisplayName("확정된 여행과 날짜가 겹치면(포함 관계) 409와 conflicts를 반환한다")
        void create_overlapsConfirmed_containment_returnsConflict() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(100);
            createConfirmedTrip(token, base, 5); // base ~ base+5, CONFIRMED

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(base.plusDays(1), 1, "WALK"))) // fully inside existing
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"))
                    .andExpect(jsonPath("$.details.conflicts[0].tripId").isNotEmpty());
        }

        @Test
        @DisplayName("확정된 여행과 날짜가 겹치면(시작쪽 부분) 409를 반환한다")
        void create_overlapsConfirmed_partialAtStart_returnsConflict() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(120);
            createConfirmedTrip(token, base.plusDays(5), 5); // existing: base+5 ~ base+10

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(base, 5, "WALK"))) // base ~ base+5, touches existing start
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"));
        }

        @Test
        @DisplayName("확정된 여행과 날짜가 겹치면(끝쪽 부분) 409를 반환한다")
        void create_overlapsConfirmed_partialAtEnd_returnsConflict() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(140);
            createConfirmedTrip(token, base, 5); // existing: base ~ base+5

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(base.plusDays(5), 5, "WALK"))) // touches existing end
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"));
        }

        @Test
        @DisplayName("확정된 여행을 완전히 포함하는 새 요청도 409를 반환한다")
        void create_overlapsConfirmed_newContainsExisting_returnsConflict() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(160);
            createConfirmedTrip(token, base.plusDays(2), 1); // existing: base+2 ~ base+3

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(base, 6, "WALK"))) // base ~ base+6, contains existing
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"));
        }

        @Test
        @DisplayName("DRAFT/CANCELLED 여행은 overlap 판정에서 제외된다")
        void create_ignoresDraftAndCancelledTrips() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(180);

            // DRAFT 상태로 남겨둔다 (확정하지 않음)
            createTrip(token, base, 5);

            // CANCELLED 상태로 별도 여행을 만든다
            MvcResult cancelled = createTrip(token, base.plusDays(1), 2);
            Long cancelledId = Long.valueOf(JsonPath.read(cancelled.getResponse().getContentAsString(), "$.id").toString());
            TripPlan cancelledTrip = tripPlanRepository.findById(cancelledId).orElseThrow();
            cancelledTrip.setStatus(TripPlanStatus.CANCELLED);
            tripPlanRepository.save(cancelledTrip);

            mockMvc.perform(post("/api/trips")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(base, 5, "WALK")))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("context 사전 확인")
    class Check {

        @Test
        @DisplayName("겹치지 않으면 available=true를 반환한다")
        void check_noConflict_returnsAvailableTrue() throws Exception {
            String token = freshToken();

            mockMvc.perform(post("/api/trips/context/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":2}
                                    """.formatted(LocalDate.now().plusDays(5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.conflicts.length()").value(0));
        }

        @Test
        @DisplayName("겹치면 available=false와 conflicts를 반환하고 저장하지 않는다")
        void check_conflict_returnsAvailableFalseWithoutSaving() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(200);
            createConfirmedTrip(token, base, 3);

            mockMvc.perform(post("/api/trips/context/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"startDate":"%s","nights":1}
                                    """.formatted(base.plusDays(1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.conflicts.length()").value(1));
        }
    }

    @Nested
    @DisplayName("context 조회")
    class Context {

        @Test
        @DisplayName("소유자는 자신의 여행 context를 조회할 수 있다")
        void getContext_owner_success() throws Exception {
            String token = freshToken();
            MvcResult created = createTrip(token, LocalDate.now().plusDays(5), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            mockMvc.perform(get("/api/trips/" + tripId + "/context").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(tripId));
        }

        @Test
        @DisplayName("존재하지 않는 여행은 404 TRIP_NOT_FOUND를 반환한다")
        void getContext_notFound() throws Exception {
            String token = freshToken();

            mockMvc.perform(get("/api/trips/999999999/context").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("타인의 여행은 404 TRIP_NOT_FOUND를 반환한다")
        void getContext_otherUser_returnsNotFound() throws Exception {
            String ownerToken = freshToken();
            String otherToken = freshToken();
            MvcResult created = createTrip(ownerToken, LocalDate.now().plusDays(5), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            mockMvc.perform(get("/api/trips/" + tripId + "/context").header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("context 수정")
    class Update {

        private String patchBody(LocalDate startDate, int nights, String transport, int version) {
            return """
                    {"startDate":"%s","nights":%d,"transport":"%s","version":%d}
                    """.formatted(startDate, nights, transport, version);
        }

        @Test
        @DisplayName("소유자가 DRAFT를 수정하면 200과 draftInvalidated=true를 반환한다")
        void updateContext_owner_success() throws Exception {
            String token = freshToken();
            MvcResult created = createTrip(token, LocalDate.now().plusDays(5), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            mockMvc.perform(patch("/api/trips/" + tripId + "/context")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody(LocalDate.now().plusDays(6), 3, "CAR", 0)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nights").value(3))
                    .andExpect(jsonPath("$.transport").value("CAR"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.draftInvalidated").value(true));
        }

        @Test
        @DisplayName("확정된 여행을 수정하려 하면 409 TRIP_CONTEXT_LOCKED를 반환한다")
        void updateContext_confirmedTrip_returnsLocked() throws Exception {
            String token = freshToken();
            Long tripId = createConfirmedTrip(token, LocalDate.now().plusDays(5), 2);

            mockMvc.perform(patch("/api/trips/" + tripId + "/context")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody(LocalDate.now().plusDays(6), 3, "CAR", 0)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_CONTEXT_LOCKED"));
        }

        @Test
        @DisplayName("버전이 다르면 409 TRIP_VERSION_CONFLICT를 반환한다")
        void updateContext_staleVersion_returnsVersionConflict() throws Exception {
            String token = freshToken();
            MvcResult created = createTrip(token, LocalDate.now().plusDays(5), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            mockMvc.perform(patch("/api/trips/" + tripId + "/context")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody(LocalDate.now().plusDays(6), 3, "CAR", 5)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_VERSION_CONFLICT"));
        }

        @Test
        @DisplayName("타인의 여행을 수정하려 하면 404 TRIP_NOT_FOUND를 반환한다")
        void updateContext_otherUser_returnsNotFound() throws Exception {
            String ownerToken = freshToken();
            String otherToken = freshToken();
            MvcResult created = createTrip(ownerToken, LocalDate.now().plusDays(5), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            mockMvc.perform(patch("/api/trips/" + tripId + "/context")
                            .header("Authorization", "Bearer " + otherToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody(LocalDate.now().plusDays(6), 3, "CAR", 0)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("변경 후 날짜가 확정 여행과 겹치면 409 TRIP_DATE_OVERLAP을 반환한다")
        void updateContext_resultingOverlap_returnsConflict() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(220);
            createConfirmedTrip(token, base, 3);

            MvcResult created = createTrip(token, base.plusDays(10), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            mockMvc.perform(patch("/api/trips/" + tripId + "/context")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(patchBody(base.plusDays(1), 1, "WALK", 0)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_DATE_OVERLAP"));
        }

        @Test
        @DisplayName("동시에 같은 버전으로 두 번 수정하면 정확히 하나만 성공한다")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void updateContext_concurrentSameVersion_onlyOneSucceeds() throws Exception {
            String token = freshToken();
            MvcResult created = createTrip(token, LocalDate.now().plusDays(5), 2);
            Long tripId = Long.valueOf(JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();

            Runnable task = () -> {
                try {
                    ready.countDown();
                    start.await();
                    int status = mockMvc.perform(patch("/api/trips/" + tripId + "/context")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(patchBody(LocalDate.now().plusDays(6), 3, "CAR", 0)))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            for (int i = 0; i < threadCount; i++) {
                executor.submit(task);
            }
            ready.await();
            start.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(threadCount - 1);
        }
    }

    @Nested
    @DisplayName("선택 불가능한 날짜")
    class UnavailableDates {

        @Test
        @DisplayName("확정된 여행 기간을 반환한다")
        void unavailableDates_returnsConfirmedTripRanges() throws Exception {
            String token = freshToken();
            LocalDate base = LocalDate.now().plusDays(300);
            createConfirmedTrip(token, base, 3);

            mockMvc.perform(get("/api/trips/unavailable-dates")
                            .header("Authorization", "Bearer " + token)
                            .param("from", base.minusDays(5).toString())
                            .param("to", base.plusDays(10).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].startDate").value(base.toString()))
                    .andExpect(jsonPath("$[0].endDate").value(base.plusDays(3).toString()));
        }
    }
}
