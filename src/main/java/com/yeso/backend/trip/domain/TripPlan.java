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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 확정된 코스. 슬롯 잠금/리롤 단계(§5.3)는 stateless라 여기 저장되지 않고,
 * 사용자가 "확정"을 눌렀을 때만 이 엔티티와 {@link TripStop}이 생긴다.
 *
 * situation은 이 여행 1회에만 적용되는 휘발성 컨텍스트(동행유형/기간 등, §5.1)를
 * JSON 문자열로 스냅샷 저장 — 나중에 취향과 구분해서 "그때 왜 이 코스가 나왔는지" 재현 가능하게.
 *
 * status는 기본값을 두지 않고 생성 시 반드시 명시한다 — v1의 유일한 생성 경로인
 * "코스 확정"(POST /api/courses)은 이 row를 만드는 순간 바로 CONFIRMED여야 하며,
 * DRAFT는 v1에 없는 미래 흐름(확정 전 서버 임시저장) 전용으로 예약해둔 상태다.
 * 필드에 기본값을 주면 실수로 DRAFT인 채 방치되는 확정 코스가 생길 수 있어 의도적으로 막는다.
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
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Transport transport;

    @Column(name = "origin_lat")
    private Double originLat;

    @Column(name = "origin_lng")
    private Double originLng;

    @Column(columnDefinition = "text")
    private String situation;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripPlanStatus status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TripPlan(User ownerUser, Region region, Transport transport, TripPlanStatus status) {
        this.ownerUser = ownerUser;
        this.region = region;
        this.transport = transport;
        this.status = status;
    }
}
