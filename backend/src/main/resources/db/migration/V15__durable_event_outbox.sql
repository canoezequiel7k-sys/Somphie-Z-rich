-- Cursor por restaurante: el lock de la fila se conserva hasta commit y ordena la publicación visible.
CREATE TABLE event_cursor (
    restaurant_id UUID PRIMARY KEY REFERENCES restaurant(id) ON DELETE CASCADE,
    last_event_id BIGINT NOT NULL DEFAULT 0 CHECK (last_event_id >= 0)
);

-- Conserva versiones monotónicas incluso para agregados sin versión propia.
CREATE TABLE event_aggregate_version (
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    aggregate_type TEXT NOT NULL CHECK (aggregate_type IN ('ORDER', 'ITEM', 'SESSION', 'TABLE', 'CATALOG')),
    aggregate_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    PRIMARY KEY (restaurant_id, aggregate_type, aggregate_id)
);

-- El sobre mínimo es suficiente para reconsultar REST y aplicar filtros antes de leer datos.
CREATE TABLE event_log (
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    id BIGINT NOT NULL CHECK (id > 0),
    type TEXT NOT NULL CHECK (type IN (
        'order.created', 'order.updated', 'item.updated', 'session.updated',
        'session.closed', 'bill.updated', 'catalog.updated', 'table.updated',
        'waiter.called', 'waiter.updated'
    )),
    aggregate_type TEXT NOT NULL CHECK (aggregate_type IN ('ORDER', 'ITEM', 'SESSION', 'TABLE', 'CATALOG')),
    aggregate_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    session_id UUID,
    station_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (restaurant_id, id)
);

CREATE INDEX idx_event_log_session_cursor
    ON event_log(restaurant_id, session_id, id)
    WHERE session_id IS NOT NULL;

CREATE INDEX idx_event_log_station_cursor
    ON event_log(restaurant_id, station_id, id)
    WHERE station_id IS NOT NULL;

CREATE INDEX idx_event_log_catalog_cursor
    ON event_log(restaurant_id, id)
    WHERE aggregate_type = 'CATALOG';
