# TourAPI 데이터 이관 계약

- 상태: 로컬 CLI 구현·검증 및 개발 RDS 이관 완료 (2026-09-27)
- 담당 범위: 내부 배치 CLI와 대상 스키마. HTTP API는 추가하지 않는다.
- 마지막 갱신일: 2026-09-27
- 관련: [기능 명세](../features/tourapi-data-migration.md)

| H2 원본 | PostgreSQL 대상 | 변환/누락 처리 |
|---|---|---|
| region.sig_cd, province, name, lat, lng | regions.sig_cd, province, city, lat, lng | 5자리 코드만; ai_summary 제외 |
| attraction.source_content_id, type | attractions.source_content_id, content_type_id, category | 출처 TOUR_API, 유형 12/14/15/28/32/38만 |
| attraction.name, sig_cd, addr, lat, lng, description | attractions.name, region_id, addr, lat, lng, description | 키/지역/이름 없으면 격리; 빈 설명은 NULL |
| attraction.homepage, usetime, restdate, parking, infocenter, tel | attractions 동명 상세 컬럼 | 공백은 NULL, 기존 값은 공백으로 덮지 않음 |
| attraction.detail_fetched, event_start_date, event_end_date | attractions.detail_fetched, event_start_date, event_end_date | 조회 시각은 추정하지 않음; 날짜 형식 검증 |
| attraction.image | attraction_images.image_url | 대표 1장, PENDING; 원천 URL/라이선스는 미확인 |
| food_place.* | restaurants + restaurant_sources | 유형 39만; source_content_id 필수; 이미지 검증 전 PENDING |
| travel_course.* | official_courses.* | 출처 TOUR_API, 원본 ID 멱등 키 |
| course_point.* | official_course_stops.* | point_index를 stop_order로; 관광지는 attraction_id, 음식점은 restaurant_id 연결. 둘 다 없으면 원본 콘텐츠 ID만 보존 |

이관 명령, 실행 ID, 품질 리포트 형식은 [런북](../runbooks/rds-postgresql-bootstrap-and-migration.md)의 WORK-09A를 따른다.
