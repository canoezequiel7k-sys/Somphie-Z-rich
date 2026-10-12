-- Migración V6: Módulo Orders (Pedidos de clientes e ítems de pedido).

CREATE TABLE customer_order (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES table_session(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL REFERENCES session_participant(id),
    status TEXT NOT NULL CHECK (status IN ('PENDING_REVIEW', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    display_code TEXT NOT NULL,
    summary TEXT NOT NULL,
    total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
    note TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    rejection_reason TEXT CHECK (rejection_reason IN ('OUT_OF_STOCK', 'KITCHEN_CLOSED', 'ITEM_UNAVAILABLE', 'OTHER')),
    rejection_text TEXT
);

CREATE TABLE order_item (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES table_session(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES product(id),
    product_name TEXT NOT NULL,
    station_id UUID NOT NULL REFERENCES station(id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    base_price_minor BIGINT NOT NULL CHECK (base_price_minor >= 0),
    options_delta_minor BIGINT NOT NULL DEFAULT 0 CHECK (options_delta_minor >= 0),
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    line_total_minor BIGINT NOT NULL CHECK (line_total_minor >= 0),
    note TEXT,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'EXCLUDED', 'IN_PREPARATION', 'READY', 'DELIVERED', 'CANCELLED')),
    started_at TIMESTAMP WITH TIME ZONE,
    ready_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE
);
