package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService;
import com.yeso.backend.attraction.application.region.CourseMaterialService.AttractionView;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.TravelTimeEstimator;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

    /** 식사를 건너뛰고 각 날의 앞 관광지에서 오는 이동시간만 다시 계산한다. */
    public void recalculateTravel(TripPlan tripPlan) {
        recalculateTravel(tripPlan, courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripPlan.getId()));
    }

    public void recalculateTravel(TripPlan tripPlan, List<CourseItem> items) {
        Map<Long, AttractionView> views = courseMaterialService.findAttractionViews(items.stream()
                .filter(item -> !item.isMeal()).map(CourseItem::getAttractionId).toList());
        for (int day = 0; day <= tripPlan.getNights(); day++) {
            AttractionView previous = null;
            for (CourseItem item : items) {
                if (item.getDayIndex() != day) {
                    continue;
                }
                if (item.isMeal()) {
                    item.updateTravelMinutesFromPrevious(null);
                    continue;
                }
                AttractionView current = views.get(item.getAttractionId());
                Integer minutes = previous == null || current == null || previous.lat() == null
                        || previous.lng() == null || current.lat() == null || current.lng() == null
                        ? null : TravelTimeEstimator.minutes(previous.lat(), previous.lng(), current.lat(), current.lng(), tripPlan.getTransport());
                item.updateTravelMinutesFromPrevious(minutes);
                previous = current;
            }
        }
    }
}
