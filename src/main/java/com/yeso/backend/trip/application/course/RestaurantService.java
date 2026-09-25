package com.yeso.backend.trip.application.course;

import com.yeso.backend.attraction.domain.Attraction;
import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.trip.application.context.TripService;
import com.yeso.backend.trip.domain.CourseInvalidOperationException;
import com.yeso.backend.trip.domain.CourseItem;
import com.yeso.backend.trip.domain.CourseItemNotFoundException;
import com.yeso.backend.trip.domain.CourseNotFoundException;
import com.yeso.backend.trip.domain.RestaurantSnapshot;
import com.yeso.backend.trip.domain.TripPlan;
import com.yeso.backend.trip.infrastructure.CourseItemRepository;
import com.yeso.backend.trip.infrastructure.KakaoLocalClient;
import com.yeso.backend.trip.presentation.course.RestaurantCandidateResponse;
import com.yeso.backend.trip.presentation.course.RestaurantOriginResponse;
import com.yeso.backend.trip.presentation.course.RestaurantSearchResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 식사 슬롯의 식당 추천·검색(docs/api/trip.md 5-5·5-6). 결과마다 {@code selectionToken}을 붙이고, 고른 식당은
 * 5-3 {@code SET_RESTAURANT}가 저장한다.
 *
 * <p>카카오 호출(최대 timeout 3초)이 DB 연결을 잡고 있지 않도록 슬롯 확인과 기준점 계산만 읽기 트랜잭션에서 하고,
 * 외부 호출은 트랜잭션 밖에서 한다.
 */
@Service
public class RestaurantService {

    static final String KAKAO_PROVIDER = "KAKAO";
    static final String KAKAO_EVIDENCE_LABEL = "카카오맵 검색 결과";

    private final TripService tripService;
    private final CourseItemRepository courseItemRepository;
    private final KakaoLocalClient kakaoLocalClient;
    private final RestaurantSelectionTokenService selectionTokenService;
    private final TransactionTemplate readOnlyTransaction;

    public RestaurantService(
            TripService tripService, CourseItemRepository courseItemRepository, KakaoLocalClient kakaoLocalClient,
            RestaurantSelectionTokenService selectionTokenService, PlatformTransactionManager transactionManager) {
        this.tripService = tripService;
        this.courseItemRepository = courseItemRepository;
        this.kakaoLocalClient = kakaoLocalClient;
        this.selectionTokenService = selectionTokenService;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /** 5-6 카카오 Local 음식점 거리순 검색. {@code keyword}가 null이면 주변 음식점 전체다. */
    public RestaurantSearchResponse search(
            Long userId, Long tripId, String itemId, String keyword, int radiusMeters, int page) {
        RestaurantOriginResponse origin = readOnlyTransaction.execute(status -> resolveOrigin(userId, tripId, itemId));
        KakaoLocalClient.Page result = kakaoLocalClient.searchRestaurants(
                new KakaoLocalClient.Query(keyword, origin.lat(), origin.lng(), radiusMeters, page));
        List<RestaurantCandidateResponse> items = result.places().stream()
                .map(place -> {
                    RestaurantSnapshot snapshot = kakaoSnapshot(place);
                    return RestaurantCandidateResponse.of(
                            selectionTokenService.issue(tripId, itemId, snapshot), snapshot, place.distanceMeters());
                })
                .toList();
        return new RestaurantSearchResponse(itemId, origin, page, result.isEnd(), items);
    }

    /**
     * 식사 슬롯의 검색 기준점. 그날 순서에서 그 식사보다 앞에 있는 관광지 중 마지막 관광지이고(사이의 다른 식사는
     * 건너뛴다), 앞에 관광지가 없으면 지역 중심 좌표다.
     */
    private RestaurantOriginResponse resolveOrigin(Long userId, Long tripId, String itemId) {
        TripPlan trip = tripService.requireParticipantTrip(userId, tripId);
        tripService.requireNotEnded(trip);
        List<CourseItem> items = courseItemRepository.findByTripPlanIdOrderByDayIndexAscOrderIndexAsc(tripId);
        if (items.isEmpty()) {
            throw new CourseNotFoundException(tripId);
        }
        CourseItem slot = items.stream()
                .filter(item -> item.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new CourseItemNotFoundException(itemId));
        if (!slot.isMeal()) {
            throw new CourseInvalidOperationException("식사 슬롯이 아닌 항목입니다: " + itemId);
        }

        Attraction previous = null;
        for (CourseItem item : items) {
            if (item == slot) {
                break;
            }
            if (item.getDayIndex() == slot.getDayIndex() && !item.isMeal()) {
                previous = item.getAttraction();
            }
        }
        if (previous != null) {
            return RestaurantOriginResponse.previousAttraction(previous.getId(), previous.getLat(), previous.getLng());
        }
        Region region = trip.getRegion();
        if (region == null || region.getLat() == null || region.getLng() == null) {
            throw new IllegalStateException("지역 중심 좌표가 없어 식당 검색 기준점을 정할 수 없습니다: trip=" + tripId);
        }
        return RestaurantOriginResponse.regionCenter(region.getLat(), region.getLng());
    }

    /** 카카오 결과는 맛·평점·대표 메뉴·사진을 만들지 않는다(docs/api/trip.md 5-6). */
    private static RestaurantSnapshot kakaoSnapshot(KakaoLocalClient.Place place) {
        return new RestaurantSnapshot(
                KAKAO_PROVIDER, place.id(), place.name(), place.categoryName(), place.address(), place.roadAddress(),
                place.lat(), place.lng(), place.phone(), place.placeUrl(), null, null,
                List.of(KAKAO_EVIDENCE_LABEL), List.of());
    }
}
