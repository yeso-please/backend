-- 지역 카드(docs/api/attraction.md 7-2) 필드. 2026-09-25 기술 결정(A-1).
-- introduction은 TEXT 그대로 두고 API가 빈 줄로 문단을 나눈다. history_tags는 쉼표로 구분한 문자열이다.
ALTER TABLE region_contents
    ADD COLUMN characteristics JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN landmarks JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN hero_image_source_name VARCHAR(255);

COMMENT ON COLUMN region_contents.landmarks IS '[{"attractionId": 5012, "order": 1}] 소개문에 나온 대표 관광지와 순서';
COMMENT ON COLUMN region_contents.sources IS '[{"title": "한국관광공사 TourAPI", "url": "https://..."}]';
