ALTER TABLE customer_order
    ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE customer_order
    DROP CONSTRAINT IF EXISTS customer_order_status_check;

ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_status_check
        CHECK (status IN ('PENDING_REVIEW', 'ACCEPTED', 'IN_PREPARATION', 'READY', 'DELIVERED', 'REJECTED', 'EXPIRED', 'CANCELLED'));

ALTER TABLE order_item
    DROP CONSTRAINT IF EXISTS order_item_status_check;

ALTER TABLE order_item
    ADD CONSTRAINT order_item_status_check
        CHECK (status IN ('PENDING', 'QUEUED', 'EXCLUDED', 'IN_PREPARATION', 'READY', 'DELIVERED', 'CANCELLED'));
