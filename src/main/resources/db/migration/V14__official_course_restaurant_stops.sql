SET search_path TO app, public;

ALTER TABLE official_course_stops
    ADD COLUMN restaurant_id BIGINT REFERENCES restaurants(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_official_course_stop_single_place CHECK (attraction_id IS NULL OR restaurant_id IS NULL);

CREATE INDEX idx_official_course_stops_restaurant ON official_course_stops(restaurant_id) WHERE restaurant_id IS NOT NULL;
