# TourAPI 데이터 이관 계약

- 상태: 최신 개발 RDS Flyway V1~V14 적용 및 TourAPI 재이관 완료 (2026-09-29)
- 담당 범위: 내부 배치 CLI와 대상 스키마. HTTP API는 추가하지 않는다.
- 마지막 갱신일: 2026-09-29
- 관련: [식당 API 계약](trip.md), [RDS 런북](../runbooks/rds-postgresql-bootstrap-and-migration.md)

| H2 원본 | PostgreSQL 대상 | 변환/누락 처리 |
|---|---|---|
| region.sig_cd, province, name, lat, lng | regions.sig_cd, province, city, lat, lng | 5자리 코드만; ai_summary 제외 |
| attraction.source_content_id, type | attractions.source_content_id, content_type_id, category | 출처 TOUR_API, 유형 12/14/15/28/32/38만 |
| attraction.name, sig_cd, addr, lat, lng, description | attractions.name, region_id, addr, lat, lng, description | 키/지역/이름 없으면 격리; 빈 설명은 NULL |
| attraction.homepage, usetime, restdate, parking, infocenter, tel | attractions 동명 상세 컬럼 | 공백은 NULL, 기존 값은 공백으로 덮지 않음 |
| attraction.detail_fetched, event_start_date, event_end_date | attractions.detail_fetched, event_start_date, event_end_date | 조회 시각은 추정하지 않음; 날짜 형식 검증 |
| attraction.image | attraction_images.image_url | 대표 1장, PENDING; 원천 URL/라이선스는 미확인 |
| food_place.source_content_id, sig_cd, name, category, addr, lat, lng | restaurants + restaurant_sources | 유형 39만; 원천 ID 필수. `addr→address`, `provider=TOUR_API`, `external_id=source_content_id`, `content_type_id=39`, `source_name=한국관광공사 TourAPI`. 좌표 누락은 V12의 NOT NULL 제약에 맞춰 격리하고 수량을 보고한다 |
| food_place.description, usetime, detail_fetched, image | restaurants.description, use_time, detail_fetched, image_url | V13 확장에 보존. 이미지 PENDING; 원본에 상세조회 시각이 없어 `restaurant_sources.fetched_at`은 NULL로 둔다 |
| travel_course.* | official_courses.* | 출처 TOUR_API, 원본 ID 멱등 키 |
| course_point.* | official_course_stops.* | point_index를 stop_order로; 관광지는 attraction_id, 음식점은 restaurant_id 연결. 둘 다 없으면 원본 콘텐츠 ID만 보존 |

이관 명령, 실행 ID, 품질 리포트 형식은 [런북](../runbooks/rds-postgresql-bootstrap-and-migration.md)의 WORK-09A를 따른다.

최신 `main`에는 식당 V12가 이미 적용돼 있으므로 이전 이관 브랜치의 동명 V12/V13 테이블 생성 SQL을 중복 적용하지 않는다. TourAPI 상세 확장은 V13, 공식 코스 음식점 연결은 V14로 추가한다. 기존 개발 RDS의 구형 V2~V4와 동일 번호의 최신 `main` V2~V4는 내용이 다르므로 기존 Flyway history를 수정하거나 `repair`하지 않는다.
