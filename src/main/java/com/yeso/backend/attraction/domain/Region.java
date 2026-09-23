package com.yeso.backend.attraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 시군구 지역. 행정표준코드(SIG_CD, 5자리)를 PK로 써서 데모/TourAPI 데이터와
 * 그대로 매칭할 수 있게 한다(데모의 domain.Region과 동일한 키 전략).
 *
 * province+city 2단계 구조는 지역 추첨(슬롯머신, API-DESIGN-DRAFT §5)에서
 * "도 → 시군구" 순으로 뽑는 데 그대로 쓰인다.
 */
@Entity
@Table(name = "regions")
@Getter
@Setter
@NoArgsConstructor
public class Region {

    @Id
    @Column(name = "sig_cd", length = 5)
    private String sigCd;

    @Column(nullable = false)
    private String province;

    @Column(nullable = false)
    private String city;

    private Double lat;

    private Double lng;

    public Region(String sigCd, String province, String city) {
        this.sigCd = sigCd;
        this.province = province;
        this.city = city;
    }
}
