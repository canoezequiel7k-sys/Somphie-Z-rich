CREATE TABLE event_retention (
    restaurant_id UUID PRIMARY KEY REFERENCES restaurant(id) ON DELETE CASCADE,
    last_deleted_event_id BIGINT NOT NULL DEFAULT 0 CHECK (last_deleted_event_id >= 0)
);
