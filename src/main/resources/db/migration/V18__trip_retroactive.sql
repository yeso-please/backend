SET search_path TO app, public;

-- 지난 여행 기록하기: 종료일이 오늘 이전인 상태로 만든 여행은 사후 기록 여행이다.
-- 기존 행은 모두 계획한 여행이므로 false다.
ALTER TABLE trip_plans ADD COLUMN retroactive BOOLEAN NOT NULL DEFAULT false;
