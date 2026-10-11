package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.application.region.CourseMaterialService.AttractionView;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseMealRestaurant;
import com.yeso.backend.trip.domain.Transport;
import com.yeso.backend.trip.domain.TravelTimeEstimator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 하루 동선의 구간(docs/api/trip.md 5장 {@code travelFromPrevious*}). 관광지와 식당을 고른 식사가 지점이고, 식당을
 * 아직 고르지 않은 식사처럼 좌표가 없는 항목은 건너뛰어 그 앞 지점에서 잇는다. 그날 첫 지점과 건너뛴 항목은 구간이 없다.
 * 이동시간 저장({@link CourseStorage}), 직선거리 응답({@link CourseService}), 지도 경로({@link CourseRouteService})가
 * 같은 규칙을 쓴다.
 */
final class TravelLegs {

    record Point(double lat, double lng) {
    }

    /** {@code fromItem}에서 도착 항목까지의 구간. 도착 항목은 {@link #of}가 돌려주는 맵의 키다. */
    record Leg(CourseItem fromItem, Point from, Point to) {
        int minutes(Transport transport) {
            return TravelTimeEstimator.minutes(from.lat(), from.lng(), to.lat(), to.lng(), transport);
        }

        int straightMeters() {
            return (int) Math.round(TravelTimeEstimator.distanceKm(from.lat(), from.lng(), to.lat(), to.lng()) * 1000);
        }
    }

    private TravelLegs() {
    }

    /**
     * @param dayItems    하루 항목(방문 순서)
     * @param attractions 관광지 ID → 현재 관광지 정보
     * @param restaurants 식사 항목 ID → 고른 식당
     * @return 구간이 있는 항목 → 구간(방문 순서)
     */
    static Map<CourseItem, Leg> of(List<CourseItem> dayItems, Map<Long, AttractionView> attractions,
                                   Map<Long, CourseMealRestaurant> restaurants) {
        Map<CourseItem, Leg> legs = new LinkedHashMap<>();
        CourseItem previousItem = null;
        Point previous = null;
        for (CourseItem item : dayItems) {
            Point current = point(item, attractions, restaurants);
            if (current == null) {
                continue;
            }
            if (previous != null) {
                legs.put(item, new Leg(previousItem, previous, current));
            }
            previousItem = item;
            previous = current;
        }
        return legs;
    }

    private static Point point(CourseItem item, Map<Long, AttractionView> attractions,
                               Map<Long, CourseMealRestaurant> restaurants) {
        Double lat;
        Double lng;
        if (item.isMeal()) {
            CourseMealRestaurant restaurant = restaurants.get(item.getId());
            lat = restaurant == null ? null : restaurant.getLat();
            lng = restaurant == null ? null : restaurant.getLng();
        } else {
            AttractionView attraction = attractions.get(item.getAttractionId());
            lat = attraction == null ? null : attraction.lat();
            lng = attraction == null ? null : attraction.lng();
        }
        return lat == null || lng == null ? null : new Point(lat, lng);
    }
}
