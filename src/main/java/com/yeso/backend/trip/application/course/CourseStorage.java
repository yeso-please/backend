package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.CourseMaterialService.AttractionView;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.TravelChain;
import com.yeso.backend.trip.domain.TravelChain.Stop;
import com.yeso.backend.trip.domain.TravelSource;
import com.yeso.backend.trip.domain.TravelTimeEstimator;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.CourseMealRestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 코스 저장 구조를 다른 유스케이스에 여는 계약. 코스 테이블(course_items, course_meal_restaurants)과
 * {@code trip_plans}의 코스 정보는 코스 기능만 직접 다루고, 지역 정하기 같은 다른 기능은 이 메서드를 쓴다.
 *
 * <p>DB가 보장하지 못해 코드로 지키는 규칙:
 * <ul>
 *   <li>코스 항목과 {@code trip_plans}의 코스 정보(제목·추천 모드 등)는 한 트랜잭션 안에서 함께 바꾼다.</li>
 *   <li>코스를 바꾸는 모든 작업은 여행 버전({@code trip_plans.version})을 올린다. 항목만 바꾸면 버전이 안 올라가
 *       동시 편집을 막지 못한다.</li>
 * </ul>
 * DB가 보장하는 규칙: 식당은 식사 항목에만 붙고, 날마다 점심·저녁은 하나씩이다(V8 migration).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseStorage {

    private final CourseItemRepository courseItemRepository;
    private final CourseMaterialService courseMaterialService;
    private final CourseMealRestaurantRepository courseMealRestaurantRepository;

    @Transactional(readOnly = true)
    public boolean hasCourse(Long tripPlanId) {
        return courseItemRepository.existsByTripPlanId(tripPlanId);
    }

    /**
     * 코스를 비운다(3-7 replaceCourse). 항목과 고른 식당, 코스 제목·추천 모드 같은 코스 정보를 지우고
     * "코스를 만든 적 있음"({@link TripPlan#getCourseFirstGeneratedAt()})은 남긴다. 여행 버전은 호출하는 쪽이 올린다.
     */
    public void emptyCourse(TripPlan tripPlan) {
        courseItemRepository.deleteByTripPlanId(tripPlan.getId());
        tripPlan.clearCourseInfo();
    }

    /**
     * 각 날의 이동값을 직선거리 추정으로 다시 저장한다(docs/api/trip.md 5장). 관광지와 고른 식당을 모두 지점으로 잇고,
     * 좌표가 없는 지점(식당 미정 등)에 닿는 구간은 비운다. 자동차 여행은 커밋 뒤 {@link CourseRouteRefresher}가 도로
     * 값으로 덮어쓴다.
     */
    public void recalculateTravel(TripPlan tripPlan) {
        recalculateTravel(tripPlan, courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripPlan.getId()));
    }

    /** @param items 날짜·순서대로 정렬된 이 여행의 모든 항목 */
    public void recalculateTravel(TripPlan tripPlan, List<CourseItem> items) {
        Map<Long, CourseItem> byId = items.stream().collect(Collectors.toMap(CourseItem::getId, Function.identity()));
        items.forEach(item -> item.updateTravel(null, null, null));
        for (List<Stop<Long>> day : stopsByDay(tripPlan, items)) {
            for (TravelChain.Leg<Long> leg : TravelChain.legs(day)) {
                int minutes = TravelTimeEstimator.minutes(leg.from().lat(), leg.from().lng(),
                        leg.to().lat(), leg.to().lng(), tripPlan.getTransport());
                byId.get(leg.to().key()).updateTravel(minutes, null, TravelSource.ESTIMATE);
            }
        }
    }

    /**
     * 날짜 순서대로 각 날의 지점 목록(방문 순서). 지점 key는 항목 id이고, 관광지는 현재 관광지 좌표, 식사는 고른 식당
     * 스냅샷 좌표다. 식당이 없으면 좌표 없는 지점이다.
     *
     * @param items 날짜·순서대로 정렬된 이 여행의 모든 항목
     */
    public List<List<Stop<Long>>> stopsByDay(TripPlan tripPlan, List<CourseItem> items) {
        Map<Long, AttractionView> views = courseMaterialService.findAttractionViews(items.stream()
                .filter(item -> !item.isMeal()).map(CourseItem::getAttractionId).toList());
        Map<Long, CourseMealRestaurant> restaurants = courseMealRestaurantRepository.findByCourseItemIdIn(items.stream()
                        .filter(CourseItem::isMeal).map(CourseItem::getId).toList()).stream()
                .collect(Collectors.toMap(CourseMealRestaurant::getCourseItemId, Function.identity()));
        List<List<Stop<Long>>> days = new ArrayList<>();
        for (int day = 0; day <= tripPlan.getNights(); day++) {
            List<Stop<Long>> stops = new ArrayList<>();
            for (CourseItem item : items) {
                if (item.getDayIndex() != day) {
                    continue;
                }
                if (item.isMeal()) {
                    CourseMealRestaurant restaurant = restaurants.get(item.getId());
                    stops.add(new Stop<>(item.getId(), restaurant == null ? null : restaurant.getLat(),
                            restaurant == null ? null : restaurant.getLng()));
                } else {
                    AttractionView view = views.get(item.getAttractionId());
                    stops.add(new Stop<>(item.getId(), view == null ? null : view.lat(), view == null ? null : view.lng()));
                }
            }
            days.add(stops);
        }
        return days;
    }
}
