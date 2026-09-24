SET search_path TO app, public;

-- 지역 추첨 전 DRAFT는 region이 없다. 날짜는 생성 시 항상 함께 정해지므로 NOT NULL로 강화한다.
ALTER TABLE trip_plans
    ALTER COLUMN region_id DROP NOT NULL,
    ALTER COLUMN start_date SET NOT NULL,
    ALTER COLUMN end_date SET NOT NULL,
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE trip_plans DROP CONSTRAINT ck_trip_status;
ALTER TABLE trip_plans ADD CONSTRAINT ck_trip_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED'));

-- lat/lng는 둘 다 있거나 둘 다 없어야 한다(XOR이 아니라 동시 null 여부 일치 검사).
ALTER TABLE trip_plans
    ADD CONSTRAINT ck_trip_origin_both_or_neither CHECK ((origin_lat IS NULL) = (origin_lng IS NULL)),
    ADD CONSTRAINT ck_trip_origin_lat CHECK (origin_lat IS NULL OR origin_lat BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_trip_origin_lng CHECK (origin_lng IS NULL OR origin_lng BETWEEN -180 AND 180);

-- trip_members는 WORK-03 이후 모든 작업서가 쓰는 trip_participants로 대체된 미사용 초기 스키마다.
DROP TABLE trip_members;

-- 여행 하나당 OWNER participant는 정확히 하나다.
CREATE UNIQUE INDEX uq_trip_participant_owner ON trip_participants(trip_plan_id) WHERE participant_type = 'OWNER';
