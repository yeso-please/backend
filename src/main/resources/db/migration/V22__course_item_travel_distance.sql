-- #101 코스 이동값의 도로 거리와 출처. 자동차 여행은 카카오모빌리티 길찾기 값(ROAD), 그 밖이나 실패 시 직선 추정(ESTIMATE).
-- 추정 구간의 거리는 저장하지 않는다(직선거리를 도로 거리처럼 보이지 않게). 기존 이동값은 모두 추정이었다.
-- 주의: 공유 개발 RDS에는 미병합 브랜치(feat/89)의 V21이 이미 적용돼 있다. 머지 순서를 맞춘다.
ALTER TABLE course_items ADD COLUMN travel_distance_meters INTEGER;
ALTER TABLE course_items ADD COLUMN travel_source VARCHAR(10);
ALTER TABLE course_items ADD CONSTRAINT ck_course_item_travel_distance
    CHECK (travel_distance_meters IS NULL OR travel_distance_meters >= 0);
ALTER TABLE course_items ADD CONSTRAINT ck_course_item_travel_source
    CHECK (travel_source IS NULL OR travel_source IN ('ROAD', 'ESTIMATE'));
UPDATE course_items SET travel_source = 'ESTIMATE' WHERE travel_minutes_from_previous IS NOT NULL;
