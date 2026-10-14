CREATE TABLE payment (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES table_session(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND')),
    method TEXT NOT NULL CHECK (method IN ('SIMULATED_ONLINE', 'CASH_COUNTER')),
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'VOIDED')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    tip_minor BIGINT NOT NULL DEFAULT 0 CHECK (tip_minor >= 0),
    payer_participant_id UUID REFERENCES session_participant(id),
    payer_label TEXT,
    reason TEXT,
    idempotency_key VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    voided_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_payment_refund CHECK (
        (kind = 'REFUND' AND amount_minor > 0 AND tip_minor = 0 AND reason IS NOT NULL)
        OR kind = 'PAYMENT'
    ),
    CONSTRAINT ck_payment_nonempty CHECK (
        kind = 'REFUND' OR amount_minor > 0 OR tip_minor > 0
    ),
    CONSTRAINT ck_payment_failed_tip CHECK (status <> 'FAILED' OR tip_minor = 0),
    CONSTRAINT ck_payment_voided_at CHECK (
        (status = 'VOIDED' AND voided_at IS NOT NULL)
        OR (status <> 'VOIDED' AND voided_at IS NULL)
    ),
    CONSTRAINT uq_payment_idempotency UNIQUE (session_id, idempotency_key)
);

CREATE INDEX idx_payment_session_created ON payment(session_id, created_at, id);

CREATE TABLE bill_adjustment (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES table_session(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('DISCOUNT_PERCENT', 'DISCOUNT_AMOUNT', 'COURTESY_ITEM', 'MANUAL_ADJUSTMENT')),
    status TEXT NOT NULL CHECK (status IN ('APPLIED', 'VOIDED')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    percent_bp INTEGER,
    order_item_id UUID REFERENCES order_item(id),
    reason_code TEXT NOT NULL,
    reason_text TEXT CHECK (reason_text IS NULL OR length(reason_text) <= 140),
    created_by UUID NOT NULL REFERENCES app_user(id),
    limit_exempt BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    voided_by UUID REFERENCES app_user(id),
    voided_at TIMESTAMP WITH TIME ZONE,
    void_reason TEXT,
    idempotency_key VARCHAR(36) NOT NULL,
    CONSTRAINT ck_adjustment_percent CHECK (
        (kind = 'DISCOUNT_PERCENT' AND percent_bp BETWEEN 1 AND 10000 AND order_item_id IS NULL)
        OR (kind <> 'DISCOUNT_PERCENT' AND percent_bp IS NULL)
    ),
    CONSTRAINT ck_adjustment_courtesy_item CHECK (
        (kind = 'COURTESY_ITEM' AND order_item_id IS NOT NULL)
        OR (kind <> 'COURTESY_ITEM' AND order_item_id IS NULL)
    ),
    CONSTRAINT ck_adjustment_voided_at CHECK (
        (status = 'VOIDED' AND voided_by IS NOT NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL)
        OR (status = 'APPLIED' AND voided_by IS NULL AND voided_at IS NULL AND void_reason IS NULL)
    ),
    CONSTRAINT uq_adjustment_idempotency UNIQUE (session_id, idempotency_key)
);

CREATE INDEX idx_adjustment_session_applied ON bill_adjustment(session_id, status, created_at, id);
CREATE INDEX idx_adjustment_actor_session ON bill_adjustment(session_id, created_by, status);
CREATE UNIQUE INDEX uq_active_item_courtesy
    ON bill_adjustment(order_item_id)
    WHERE kind = 'COURTESY_ITEM' AND status = 'APPLIED';
