package com.yeso.backend.attraction.infrastructure;

import com.yeso.backend.attraction.domain.Region;
import org.springframework.data.jpa.repository.JpaRepository;

/** 지역(시군구) 저장소. profile·trip의 지역 조회는 MVP 예외로 직접 쓴다(docs/conventions/모듈-의존성.md). */
public interface RegionRepository extends JpaRepository<Region, String> {
}
