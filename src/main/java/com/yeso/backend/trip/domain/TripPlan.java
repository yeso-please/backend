package com.yeso.backend.trip.domain;

import com.yeso.backend.attraction.domain.Region;
import com.yeso.backend.auth.domain.User;
import com.yeso.backend.shared.persistence.BaseTimeEntity;
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

/**
 * 여행 방. 만드는 순간 기간을 차지하며 날짜는 바꿀 수 없다(2026-09-24 정책). 지역은 지역 정하기(API 3-7)가
 * 정하기 전까지 null이다. {@code ownerUser}는 만든 사람이며 권한 차이는 없다 — 참여 여부는
 * {@link TripParticipant}가 판정한다. 확정 단계가 없어 {@code status}는 항상 DRAFT로 남는다.
 *
 * {@code version}은 낙관적 잠금이다 — 동시에 두 PATCH가 들어오면 하나는
 * {@code TRIP_VERSION_CONFLICT}로 거부돼야 하므로 JPA {@link Version}을 그대로 쓴다.
 */
@Entity
@Table(name = "trip_plans")
@Getter
@Setter
@NoArgsConstructor
public class TripPlan extends BaseTimeEntity {

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

    /** 지역을 정한 방식(RANDOM·CONDITIONAL·MANUAL). 지역 정하기(API 3-7)가 채운다. */
    @Column(name = "region_selection", length = 20)
    private String regionSelection;

    /** 지역을 정할 때 쓴 일정 밀도(RELAXED·PACKED). 지역 정하기(API 3-7)가 채운다. */
    @Column(name = "schedule_density", length = 20)
    private String scheduleDensity;

    @Version
    @Column(nullable = false)
    private int version;

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

    /** 종료일이 오늘보다 앞이면 끝난 여행이다(오늘이 종료일이면 아직 진행 중). */
    public boolean isEnded(LocalDate today) {
        return endDate.isBefore(today);
    }

    /** 참여자 정원. 가득 차면 초대를 수락할 수 없다(docs/api/trip.md 4장). */
    public static final int MAX_PARTICIPANTS = 8;

    /**
     * 화면에 보일 여행 이름. 코스 제목이 없으면 "M월 D일부터 N박 N+1일 여행"(당일은 "M월 D일 당일 여행")이다
     * (docs/api/trip.md 3-6).
     */
    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        String start = startDate.getMonthValue() + "월 " + startDate.getDayOfMonth() + "일";
        int nights = getNights();
        return nights == 0 ? start + " 당일 여행" : start + "부터 " + nights + "박 " + (nights + 1) + "일 여행";
    }

    public boolean isCreatedBy(Long userId) {
        return ownerUser.getId().equals(userId);
    }

    /** 날짜는 바꿀 수 없다. 이동수단은 null이면 유지하고, 출발지는 그대로 덮어쓴다(둘 다 null이면 삭제). */
    public void updateTransportAndOrigin(Transport transport, Double originLat, Double originLng) {
        if (transport != null) {
            this.transport = transport;
        }
        this.originLat = originLat;
        this.originLng = originLng;
    }

    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !startDate.isAfter(otherEnd) && !endDate.isBefore(otherStart);
    }

    private void applyDates(LocalDate startDate, int nights) {
        this.startDate = startDate;
        this.endDate = startDate.plusDays(nights);
    }
}
