SET search_path TO app, public;

-- 노출 기록에 취향 점수를 매긴 순위 모델 버전을 남긴다(2026-10-27, docs/design/recommendation.md §5).
-- null이면 코사인 유사도(또는 취향 미사용)로 뽑은 것이다. 정책별 오프라인 평가에서 둘을 구분한다.
ALTER TABLE recommendation_impressions ADD COLUMN ranker_version VARCHAR(100);
