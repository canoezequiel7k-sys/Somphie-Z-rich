ALTER TABLE customer_order
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    ADD COLUMN decided_by UUID REFERENCES app_user(id) ON DELETE SET NULL;

ALTER TABLE order_item
    ADD COLUMN options_snapshot JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN product_name_i18n JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN queued_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN cancellation_reason TEXT;

CREATE INDEX idx_order_item_station_queue
    ON order_item (station_id, status, queued_at, id);
