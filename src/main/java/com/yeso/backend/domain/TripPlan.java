package com.yeso.backend.domain;

import com.yeso.backend.domain.enums.Transport;
import com.yeso.backend.domain.enums.TripPlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
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

    @Lob
    private String situation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripPlanStatus status = TripPlanStatus.DRAFT;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
