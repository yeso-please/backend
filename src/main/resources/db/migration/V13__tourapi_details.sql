SET search_path TO app, public;

ALTER TABLE attractions
    ADD COLUMN detail_fetched BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN event_start_date DATE,
    ADD COLUMN event_end_date DATE,
    ADD CONSTRAINT ck_attractions_event_dates CHECK (
        event_start_date IS NULL OR event_end_date IS NULL OR event_end_date >= event_start_date
    );

-- The demo H2 source records whether detail was fetched, but not when.
-- Preserve the details without inventing a TourAPI fetch timestamp.
ALTER TABLE restaurants
    ADD COLUMN description TEXT,
    ADD COLUMN use_time TEXT,
    ADD COLUMN detail_fetched BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE restaurant_sources
    ALTER COLUMN fetched_at DROP NOT NULL;
