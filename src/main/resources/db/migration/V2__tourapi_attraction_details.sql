SET search_path TO app, public;

ALTER TABLE attractions
    ADD COLUMN detail_fetched BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN event_start_date DATE,
    ADD COLUMN event_end_date DATE,
    ADD CONSTRAINT ck_attractions_event_dates CHECK (
        event_start_date IS NULL OR event_end_date IS NULL OR event_end_date >= event_start_date
    );
