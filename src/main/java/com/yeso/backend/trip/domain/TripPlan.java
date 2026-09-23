package com.yeso.backend.trip.domain;

import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 여행 방. 지역 추첨 전에는 region이 null인 DRAFT다(WORK-03). 확정(WORK-08) 이후에는
 * CONFIRMED로 바뀌고 context(날짜/이동수단/출발지) 수정이 잠긴다({@link #isMutable()}).
 *
 * {@code version}은 낙관적 잠금이다 — 동시에 두 PATCH가 들어오면 하나는
 * {@code TRIP_VERSION_CONFLICT}로 거부돼야 하므로 JPA {@link Version}을 그대로 쓴다.
 */
@Entity
@Table(name = "trip_plans")
@Getter
@Setter
@NoArgsConstructor
public class TripPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User ownerUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region;

    @Column(length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Transport transport;

    @Column(name = "origin_lat")
    private Double originLat;

    @Column(name = "origin_lng")
    private Double originLng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripPlanStatus status;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public TripPlan(User ownerUser, LocalDate startDate, int nights, Transport transport, Double originLat, Double originLng) {
        this.ownerUser = ownerUser;
        this.transport = transport;
        this.originLat = originLat;
        this.originLng = originLng;
        this.status = TripPlanStatus.DRAFT;
        applyDates(startDate, nights);
    }

    public int getNights() {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
    }

    public boolean isMutable() {
        return status == TripPlanStatus.DRAFT;
    }

    public boolean isOwnedBy(Long userId) {
        return ownerUser.getId().equals(userId);
    }

    public void updateContext(LocalDate startDate, int nights, Transport transport, Double originLat, Double originLng) {
        applyDates(startDate, nights);
        this.transport = transport;
        this.originLat = originLat;
        this.originLng = originLng;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !startDate.isAfter(otherEnd) && !endDate.isBefore(otherStart);
    }

    private void applyDates(LocalDate startDate, int nights) {
        this.startDate = startDate;
        this.endDate = startDate.plusDays(nights);
    }
}
