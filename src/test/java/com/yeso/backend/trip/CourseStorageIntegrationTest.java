package com.yeso.backend.trip;

import com.yeso.backend.attraction.domain.Attraction;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.support.ApiFixtures.Member;
import com.yeso.backend.support.IntegrationTest;
import com.yeso.backend.trip.application.course.CourseStorage;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseItemSource;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.CourseTitleSource;
import com.yeso.backend.trip.domain.MealType;
import com.yeso.backend.trip.domain.RecommendationMode;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
import com.yeso.backend.trip.infrastructure.TripPlanRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 코스 저장 구조(course_items, course_meal_restaurants, trip_plans 코스 정보)와 코스 비우기.
 * 코스 생성 API(5-1) 전이라 준비는 Repository로 한다.
 */
class CourseStorageIntegrationTest extends IntegrationTest {

    @Autowired
    private CourseStorage courseStorage;

    @Autowired
    private CourseItemRepository courseItemRepository;

    @Autowired
    private CourseMealRestaurantRepository courseMealRestaurantRepository;

    @Autowired
    private TripPlanRepository tripPlanRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long insertAttraction(String name) {
        jdbcTemplate.update("insert into app.regions (sig_cd, province, city) values ('47130', '경상북도', '경주시') on conflict do nothing");
        return jdbcTemplate.queryForObject(
                "insert into app.attractions (name, category, region_id) values (?, '역사', '47130') returning id", Long.class, name);
    }

    /** 1일차: 대릉원 → 점심(식당 선택) → 첨성대. 코스 정보도 채운다. */
    private Long tripWithCourse(Member member) throws Exception {
        Long tripId = fixtures.createTrip(member.accessToken(), clock.today().plusDays(10), 1);
        Long first = insertAttraction("대릉원");
        Long second = insertAttraction("첨성대");
        transactionTemplate.executeWithoutResult(status -> {
            TripPlan trip = tripPlanRepository.findById(tripId).orElseThrow();
            User user = entityManager.getReference(User.class, member.userId());
            trip.setTitle("경주, 역사를 따라 걷는 2일");
            trip.setTitleSource(CourseTitleSource.RULE);
            trip.setRecommendationMode(RecommendationMode.PERSONALIZED);
            trip.setTasteBasisUser(user);
            trip.setCourseFirstGeneratedAt(clock.now());

            courseItemRepository.save(CourseItem.attraction(
                    trip, 0, 0, entityManager.getReference(Attraction.class, first), 90, null, CourseItemSource.RECOMMEND, "역사 선호"));
            CourseItem lunch = courseItemRepository.save(CourseItem.meal(trip, 0, 1, MealType.LUNCH));
            courseItemRepository.save(CourseItem.attraction(
                    trip, 0, 2, entityManager.getReference(Attraction.class, second), 60, 10, CourseItemSource.RECOMMEND, null));
            courseMealRestaurantRepository.save(new CourseMealRestaurant(
                    lunch, "TOUR_API", "2871024", "황남맷돌순두부", "한식", "경북 경주시", null, 35.83, 129.21,
                    "054-000-0000", null, "https://img.example/1.jpg", "순두부찌개",
                    List.of("한국관광공사 등록 음식점"),
                    List.of(new CourseMealRestaurant.Source("한국관광공사 TourAPI", "https://api.example", "2026-09-30T00:00:00")),
                    user, clock.now()));
        });
        return tripId;
    }

    @Test
    @DisplayName("관광지와 식사를 한 순서 목록으로 저장하고, 고른 식당 스냅샷을 그대로 읽는다")
    void save_orderedListWithRestaurant() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripWithCourse(member);

        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        assertThat(items).extracting(CourseItem::isMeal).containsExactly(false, true, false);
        assertThat(items.get(1).itemId()).isEqualTo("m-" + items.get(1).getId());
        assertThat(items.get(0).itemId()).isEqualTo("a-" + items.get(0).getId());
        assertThat(items.get(1).getStayMinutes()).isEqualTo(CourseItem.MEAL_STAY_MINUTES);

        CourseMealRestaurant restaurant = courseMealRestaurantRepository.findById(items.get(1).getId()).orElseThrow();
        assertThat(restaurant.getEvidenceLabels()).containsExactly("한국관광공사 등록 음식점");
        assertThat(restaurant.getSources()).extracting(CourseMealRestaurant.Source::name).containsExactly("한국관광공사 TourAPI");

        mockMvc.perform(get("/api/trips").header("Authorization", member.bearer()))
                .andExpect(jsonPath("$[0].hasCourse").value(true))
                .andExpect(jsonPath("$[0].title").value("경주, 역사를 따라 걷는 2일"));
    }

    @Test
    @DisplayName("코스를 비우면 항목·식당·코스 정보가 지워지고 '만든 적 있음' 기록만 남는다")
    void emptyCourse_keepsFirstGeneratedOnly() throws Exception {
        Member member = fixtures.onboardedMember();
        Long tripId = tripWithCourse(member);

        transactionTemplate.executeWithoutResult(status ->
                courseStorage.emptyCourse(tripPlanRepository.findById(tripId).orElseThrow()));

        assertThat(courseItemRepository.existsByTripPlanId(tripId)).isFalse();
        assertThat(courseMealRestaurantRepository.count()).isZero();
        TripPlan trip = tripPlanRepository.findById(tripId).orElseThrow();
        assertThat(trip.getTitle()).isNull();
        assertThat(trip.getTitleSource()).isNull();
        assertThat(trip.getRecommendationMode()).isNull();
        assertThat(trip.getCourseFirstGeneratedAt()).isEqualTo(clock.now());
        assertThat(trip.hasGeneratedCourse()).isTrue();

        mockMvc.perform(get("/api/trips").header("Authorization", member.bearer()))
                .andExpect(jsonPath("$[0].hasCourse").value(false))
                .andExpect(jsonPath("$[0].title").value("10월 11일부터 1박 2일 여행"));
    }

    @Test
    @DisplayName("같은 날 두 항목의 순서를 맞바꿔도 커밋 시점에만 순서 중복을 검사한다")
    void swapOrder_isAllowedWithinTransaction() throws Exception {
        Long tripId = tripWithCourse(fixtures.onboardedMember());

        transactionTemplate.executeWithoutResult(status -> {
            List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
            items.get(0).moveTo(0, 1); // 대릉원 ↔ 점심
            items.get(1).moveTo(0, 0);
            entityManager.flush();
        });

        assertThat(courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId))
                .extracting(CourseItem::isMeal).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("관광지 항목에 관광지가 없거나 식사 항목에 점심·저녁 구분이 없으면 DB가 거부한다")
    void shapeConstraint_rejectsInvalidRows() throws Exception {
        Long tripId = fixtures.createTrip(fixtures.onboardedMember().accessToken(), clock.today().plusDays(10), 1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into app.course_items (trip_plan_id, day_index, order_index, kind, stay_minutes, source) values (?, 0, 0, 'ATTRACTION', 60, 'MANUAL')",
                tripId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into app.course_items (trip_plan_id, day_index, order_index, kind, stay_minutes) values (?, 0, 0, 'MEAL', 60)",
                tripId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("SQL로 직접 넣어도 식당은 관광지 항목에 붙지 않는다")
    void restaurantOnAttraction_rejectedByDatabase() throws Exception {
        Long tripId = tripWithCourse(fixtures.onboardedMember());
        Long attractionItemId = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId).get(0).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into app.course_meal_restaurants (course_item_id, provider, external_id, name, selected_at) values (?, 'KAKAO', '1', '식당', now())",
                attractionItemId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 날에 점심을 두 개 넣으면 DB가 거부하고, 다른 날에는 들어간다")
    void mealPerDay_isUnique() throws Exception {
        Long tripId = tripWithCourse(fixtures.onboardedMember()); // 1일차에 점심이 있다

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into app.course_items (trip_plan_id, day_index, order_index, kind, meal_type, stay_minutes) values (?, 0, 9, 'MEAL', 'LUNCH', 60)",
                tripId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update(
                "insert into app.course_items (trip_plan_id, day_index, order_index, kind, meal_type, stay_minutes) values (?, 1, 0, 'MEAL', 'LUNCH', 60)",
                tripId);
    }

    @Test
    @DisplayName("식사는 같은 날 안에서만 옮길 수 있다")
    void moveMeal_toAnotherDay_isRejected() throws Exception {
        Long tripId = tripWithCourse(fixtures.onboardedMember());
        CourseItem lunch = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId).get(1);

        assertThatThrownBy(() -> lunch.moveTo(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("식당은 식사 항목에만 고를 수 있다")
    void restaurant_onlyOnMeal() throws Exception {
        Long tripId = tripWithCourse(fixtures.onboardedMember());
        CourseItem attraction = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId).get(0);

        assertThatThrownBy(() -> new CourseMealRestaurant(
                attraction, "KAKAO", "1", "식당", null, null, null, null, null, null, null, null, null,
                null, null, null, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
