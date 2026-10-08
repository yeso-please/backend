package com.yeso.backend.attraction.application.region;

import com.yeso.backend.attraction.infrastructure.RestaurantQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Trip 모듈이 식당 DB를 직접 읽지 않도록 제공하는 조회 계약. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantQueryService {
    private final RestaurantQueryRepository repository;

    public record Candidate(String externalId, String name, String category, String address,
                            String roadAddress, String phone, double lat, double lng, String placeUrl,
                            String imageUrl, String representativeMenu, String sourceName, String sourceUrl,
                            LocalDateTime fetchedAt, int distanceMeters) {
    }

    public List<Candidate> findTourApi(String sigCd, double originLat, double originLng, int radiusMeters) {
        return loadTourApi(sigCd).nearest(originLat, originLng, radiusMeters, 20);
    }

    /** 지역의 TourAPI 식당을 한 번 읽어 둔다. 기준점이 여러 개여도 DB를 다시 읽지 않고 {@link RegionRestaurants#nearest}로 거른다. */
    public RegionRestaurants loadTourApi(String sigCd) {
        return new RegionRestaurants(repository.findTourApi(sigCd));
    }

    public static final class RegionRestaurants {
        private final List<RestaurantQueryRepository.Row> rows;

        private RegionRestaurants(List<RestaurantQueryRepository.Row> rows) {
            this.rows = rows;
        }

        /** 기준점에서 {@code radiusMeters} 이내인 식당을 가까운 순으로 최대 {@code limit}개. */
        public List<Candidate> nearest(double originLat, double originLng, int radiusMeters, int limit) {
            return rows.stream()
                    .map(row -> new Candidate(row.externalId(), row.name(), row.category(), row.address(),
                            row.roadAddress(), row.phone(), row.lat(), row.lng(), row.placeUrl(), row.imageUrl(),
                            row.representativeMenu(), row.sourceName(), row.sourceUrl(), row.fetchedAt(),
                            (int) Math.round(distanceKm(originLat, originLng, row.lat(), row.lng()) * 1000)))
                    .filter(candidate -> candidate.distanceMeters() <= radiusMeters)
                    .sorted(Comparator.comparingInt(Candidate::distanceMeters).thenComparing(Candidate::externalId))
                    .limit(limit)
                    .toList();
        }
    }

    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.asin(Math.sqrt(a));
    }
}
