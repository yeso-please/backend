package com.yeso.backend.trip.domain;

import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 식사 항목에 고른 식당의 스냅샷(docs/api/trip.md 5장 RestaurantSnapshot). 고른 순간의 정보를 복사해 두고
 * 코스를 볼 때 그대로 보여준다. 식사 한 자리에 식당 하나이며, 식당을 해제하면 행을 지운다.
 */
@Entity
@Table(name = "course_meal_restaurants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseMealRestaurant {

    @Id
    @Column(name = "course_item_id")
    private Long courseItemId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_item_id")
    private CourseItem courseItem;

    /** {@code TOUR_API} | {@code FARM_RESTAURANT} | {@code MODEL_RESTAURANT} | {@code GOOD_PRICE} | {@code KAKAO}. */
    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(nullable = false)
    private String name;

    @Column
    private String category;

    @Column
    private String address;

    @Column(name = "road_address")
    private String roadAddress;

    @Column
    private Double lat;

    @Column
    private Double lng;

    @Column(length = 100)
    private String phone;

    @Column(name = "place_url")
    private String placeUrl;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "representative_menu")
    private String representativeMenu;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_labels", nullable = false, columnDefinition = "jsonb")
    private List<String> evidenceLabels = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Source> sources = List.of();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_by_user_id")
    private User selectedBy;

    @Column(name = "selected_at", nullable = false)
    private LocalDateTime selectedAt;

    /** 스냅샷 출처 한 건. {@code fetchedAt}은 원천이 준 문자열 그대로 둔다. */
    public record Source(String name, String url, String fetchedAt) {
    }

    public CourseMealRestaurant(
            CourseItem courseItem, String provider, String externalId, String name, String category,
            String address, String roadAddress, Double lat, Double lng, String phone, String placeUrl,
            String imageUrl, String representativeMenu, List<String> evidenceLabels, List<Source> sources,
            User selectedBy, LocalDateTime selectedAt) {
        if (!courseItem.isMeal()) {
            throw new IllegalArgumentException("식당은 식사 항목에만 고를 수 있습니다: " + courseItem.getId());
        }
        this.courseItem = courseItem;
        this.provider = provider;
        this.externalId = externalId;
        this.name = name;
        this.category = category;
        this.address = address;
        this.roadAddress = roadAddress;
        this.lat = lat;
        this.lng = lng;
        this.phone = phone;
        this.placeUrl = placeUrl;
        this.imageUrl = imageUrl;
        this.representativeMenu = representativeMenu;
        this.evidenceLabels = evidenceLabels == null ? List.of() : List.copyOf(evidenceLabels);
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.selectedBy = selectedBy;
        this.selectedAt = selectedAt;
    }
}
