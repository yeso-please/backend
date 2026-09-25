package com.yeso.backend.trip;

import com.yeso.backend.attraction.domain.Attraction;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import com.yeso.backend.trip.application.course.RestaurantSelectionTokenService;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseItemSource;
import com.yeso.backend.trip.domain.MealType;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.FakeKakaoLocalClient;
import com.yeso.backend.trip.infrastructure.KakaoLocalClient;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 식당 검색(docs/api/trip.md 5-6). 코스 생성 API(5-1)와 지역 정하기(3-7) 전이라 코스·지역 준비는 Repository로 한다
 * ({@link CourseStorageIntegrationTest}와 같은 방식). 5-1이 생기면 준비를 API로 옮긴다.
 */
class RestaurantIntegrationTest extends IntegrationTest {

    private static final String SEARCH_URL = "/api/courses/{tripId}/restaurants/search";

    private static final double REGION_LAT = 35.856;
    private static final double REGION_LNG = 129.225;
    private static final double FIRST_LAT = 35.838;
    private static final double FIRST_LNG = 129.211;

    @Autowired
    private CourseItemRepository courseItemRepository;

    @Autowired
    private TripPlanRepository tripPlanRepository;

    @Autowired
    private RestaurantSelectionTokenService selectionTokenService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void insertRegion() {
        jdbcTemplate.update("insert into app.regions (sig_cd, province, city, lat, lng) values ('47130', '경상북도', '경주시', ?, ?)",
                REGION_LAT, REGION_LNG);
    }

    private Long insertAttraction(String name, double lat, double lng) {
        return jdbcTemplate.queryForObject(
                "insert into app.attractions (name, category, region_id, lat, lng) values (?, '역사', '47130', ?, ?) returning id",
                Long.class, name, lat, lng);
    }

    /**
     * 경주 1박 2일 코스.
     * <pre>
     * 1일차: 대릉원 → 점심 → 저녁 → 첨성대
     * 2일차: 점심 → 불국사 → 저녁
     * </pre>
     */
    private record Course(Long tripId, LocalDate endDate, Long firstAttractionId, String firstItemId,
                          String day0Lunch, String day0Dinner, String day1Lunch) {
    }

    private Course tripWithCourse(Member member) throws Exception {
        LocalDate startDate = clock.today().plusDays(10);
        Long tripId = fixtures.createTrip(member.accessToken(), startDate, 1);
        Long first = insertAttraction("대릉원", FIRST_LAT, FIRST_LNG);
        Long second = insertAttraction("첨성대", 35.835, 129.219);
        Long third = insertAttraction("불국사", 35.790, 129.332);
        List<CourseItem> saved = transactionTemplate.execute(status -> {
            TripPlan trip = tripPlanRepository.findById(tripId).orElseThrow();
            trip.setRegion(entityManager.getReference(Region.class, "47130"));
            return courseItemRepository.saveAll(List.of(
                    attraction(trip, 0, 0, first),
                    CourseItem.meal(trip, 0, 1, MealType.LUNCH),
                    CourseItem.meal(trip, 0, 2, MealType.DINNER),
                    attraction(trip, 0, 3, second),
                    CourseItem.meal(trip, 1, 0, MealType.LUNCH),
                    attraction(trip, 1, 1, third),
                    CourseItem.meal(trip, 1, 2, MealType.DINNER)));
        });
        return new Course(tripId, startDate.plusDays(1), first, saved.get(0).itemId(),
                saved.get(1).itemId(), saved.get(2).itemId(), saved.get(4).itemId());
    }

    private CourseItem attraction(TripPlan trip, int day, int order, Long attractionId) {
        return CourseItem.attraction(trip, day, order, entityManager.getReference(Attraction.class, attractionId),
                90, null, CourseItemSource.RECOMMEND, null);
    }

    private static KakaoLocalClient.Place kalguksu() {
        return new KakaoLocalClient.Place("12345678", "황남칼국수", "음식점 > 한식 > 국수", "경북 경주시 황남동 1",
                "경북 경주시 포석로 1", "054-000-0000", 35.840, 129.212, 380, "https://place.map.kakao.com/12345678");
    }

    @Nested
    @DisplayName("5-6 식당 검색")
    class Search {

        @Test
        @DisplayName("식사 앞 관광지를 기준점으로 카카오를 검색하고 선택 토큰을 붙여 반환한다")
        void search_success() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);
            fakeKakaoLocalClient.setPage(new KakaoLocalClient.Page(List.of(kalguksu()), false));

            MvcResult result = mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Lunch())
                            .param("query", "칼국수"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemId").value(course.day0Lunch()))
                    .andExpect(jsonPath("$.origin.type").value("PREVIOUS_ATTRACTION"))
                    .andExpect(jsonPath("$.origin.attractionId").value(course.firstAttractionId()))
                    .andExpect(jsonPath("$.origin.lat").value(FIRST_LAT))
                    .andExpect(jsonPath("$.origin.lng").value(FIRST_LNG))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.isEnd").value(false))
                    .andExpect(jsonPath("$.items[0].selectionToken").value(startsWith("rs_")))
                    .andExpect(jsonPath("$.items[0].provider").value("KAKAO"))
                    .andExpect(jsonPath("$.items[0].externalId").value("12345678"))
                    .andExpect(jsonPath("$.items[0].name").value("황남칼국수"))
                    .andExpect(jsonPath("$.items[0].category").value("음식점 > 한식 > 국수"))
                    .andExpect(jsonPath("$.items[0].address").value("경북 경주시 황남동 1"))
                    .andExpect(jsonPath("$.items[0].roadAddress").value("경북 경주시 포석로 1"))
                    .andExpect(jsonPath("$.items[0].lat").value(35.840))
                    .andExpect(jsonPath("$.items[0].lng").value(129.212))
                    .andExpect(jsonPath("$.items[0].distanceMeters").value(380))
                    .andExpect(jsonPath("$.items[0].phone").value("054-000-0000"))
                    .andExpect(jsonPath("$.items[0].placeUrl").value("https://place.map.kakao.com/12345678"))
                    .andExpect(jsonPath("$.items[0].imageUrl").isEmpty())
                    .andExpect(jsonPath("$.items[0].representativeMenu").isEmpty())
                    .andExpect(jsonPath("$.items[0].evidenceLabels[0]").value("카카오맵 검색 결과"))
                    .andExpect(jsonPath("$.items[0].sources").isEmpty())
                    .andReturn();

            assertThat(fakeKakaoLocalClient.queries()).containsExactly(
                    new KakaoLocalClient.Query("칼국수", FIRST_LAT, FIRST_LNG, 5000, 1));

            String token = jsonMapper.readTree(result.getResponse().getContentAsString())
                    .get("items").get(0).get("selectionToken").asString();
            RestaurantSnapshot snapshot = selectionTokenService.verify(token, course.tripId(), course.day0Lunch());
            assertThat(snapshot.provider()).isEqualTo("KAKAO");
            assertThat(snapshot.name()).isEqualTo("황남칼국수");
        }

        @Test
        @DisplayName("사이에 다른 식사가 있으면 건너뛰고 그 앞 관광지를 기준점으로 쓴다")
        void search_skipsMealBetween() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Dinner()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.origin.type").value("PREVIOUS_ATTRACTION"))
                    .andExpect(jsonPath("$.origin.attractionId").value(course.firstAttractionId()));
        }

        @Test
        @DisplayName("그날 식사 앞에 관광지가 없으면 지역 중심을 기준점으로 쓰고, 검색어를 생략하면 주변 전체를 찾는다")
        void search_noPreviousAttraction_usesRegionCenter() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day1Lunch()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.origin.type").value("REGION_CENTER"))
                    .andExpect(jsonPath("$.origin.attractionId").doesNotExist())
                    .andExpect(jsonPath("$.origin.lat").value(REGION_LAT))
                    .andExpect(jsonPath("$.isEnd").value(true))
                    .andExpect(jsonPath("$.items").isEmpty());

            assertThat(fakeKakaoLocalClient.queries()).containsExactly(
                    new KakaoLocalClient.Query(null, REGION_LAT, REGION_LNG, 5000, 1));
        }

        @Test
        @DisplayName("radius 20000과 page 45는 허용하고 그대로 카카오에 넘긴다")
        void search_upperBounds_allowed() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Lunch())
                            .param("radius", "20000")
                            .param("page", "45"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(45));

            assertThat(fakeKakaoLocalClient.queries()).extracting(KakaoLocalClient.Query::radiusMeters, KakaoLocalClient.Query::page)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(20000, 45));
        }

        @Test
        @DisplayName("radius·page·query가 범위 밖이면 400 COMMON_INVALID_REQUEST와 fieldErrors다")
        void search_outOfRange_returnsBadRequest() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Lunch())
                            .param("radius", "20001")
                            .param("page", "46")
                            .param("query", "가".repeat(51)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fieldErrors[*].field").value(
                            org.hamcrest.Matchers.containsInAnyOrder("radius", "page", "query")));

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("itemId"));

            assertThat(fakeKakaoLocalClient.queries()).isEmpty();
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH_UNAUTHENTICATED다")
        void search_withoutToken_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(SEARCH_URL, 1).param("itemId", "m-1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("참여자가 아니거나 없는 여행이면 404 TRIP_NOT_FOUND다")
        void search_notParticipant_returnsNotFound() throws Exception {
            Member owner = fixtures.onboardedMember();
            Member stranger = fixtures.onboardedMember();
            Course course = tripWithCourse(owner);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", stranger.bearer())
                            .param("itemId", course.day0Lunch()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));

            mockMvc.perform(get(SEARCH_URL, 999_999)
                            .header("Authorization", owner.bearer())
                            .param("itemId", course.day0Lunch()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }

        @Test
        @DisplayName("종료일이 지난 여행이면 409 TRIP_ENDED다")
        void search_endedTrip_returnsConflict() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);
            clock.setTo(course.endDate().plusDays(1));

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Lunch()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_ENDED"));
        }

        @Test
        @DisplayName("코스가 없으면 404 COURSE_NOT_FOUND다")
        void search_noCourse_returnsNotFound() throws Exception {
            Member me = fixtures.onboardedMember();
            Long tripId = fixtures.createTrip(me.accessToken(), clock.today().plusDays(10), 1);

            mockMvc.perform(get(SEARCH_URL, tripId)
                            .header("Authorization", me.bearer())
                            .param("itemId", "m-1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
        }

        @Test
        @DisplayName("코스에 없는 항목이면 404 COURSE_ITEM_NOT_FOUND다")
        void search_unknownItem_returnsNotFound() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", "m-999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COURSE_ITEM_NOT_FOUND"));
        }

        @Test
        @DisplayName("관광지 항목이면 400 COURSE_INVALID_OPERATION이다")
        void search_attractionItem_returnsBadRequest() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.firstItemId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COURSE_INVALID_OPERATION"));
        }

        @Test
        @DisplayName("카카오 장애면 502 COURSE_KAKAO_LOCAL_UNAVAILABLE이다")
        void search_kakaoUnavailable_returnsBadGateway() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);
            fakeKakaoLocalClient.setMode(FakeKakaoLocalClient.Mode.UNAVAILABLE);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Lunch()))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("COURSE_KAKAO_LOCAL_UNAVAILABLE"));
        }

        @Test
        @DisplayName("카카오 호출 한도면 503 COURSE_KAKAO_LOCAL_RATE_LIMITED와 Retry-After다")
        void search_kakaoRateLimited_returnsServiceUnavailable() throws Exception {
            Member me = fixtures.onboardedMember();
            Course course = tripWithCourse(me);
            fakeKakaoLocalClient.setMode(FakeKakaoLocalClient.Mode.RATE_LIMITED);

            mockMvc.perform(get(SEARCH_URL, course.tripId())
                            .header("Authorization", me.bearer())
                            .param("itemId", course.day0Lunch()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.code").value("COURSE_KAKAO_LOCAL_RATE_LIMITED"));
        }
    }
}
