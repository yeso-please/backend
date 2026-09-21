package com.yeso.backend.onboarding.infrastructure;

import com.yeso.backend.domain.Region;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * region/attraction 전용 bounded-context 패키지가 아직 없어(WORK-05 예정) 임시로 여기 둔다 —
 * region 소유 도메인이 생기면 그쪽으로 옮긴다(docs/conventions/모듈-의존성.md).
 */
public interface RegionRepository extends JpaRepository<Region, String> {
}
