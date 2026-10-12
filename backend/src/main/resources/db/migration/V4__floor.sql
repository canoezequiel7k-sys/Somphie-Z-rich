-- Migración V4: Módulo Floor (Mesas, sesiones de mesa, participantes y códigos QR).

CREATE TABLE dining_table (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    table_code TEXT NOT NULL UNIQUE,
    qr_version INTEGER NOT NULL DEFAULT 1,
    capacity INTEGER NOT NULL DEFAULT 4 CHECK (capacity > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (restaurant_id, label)
);

CREATE TABLE table_session (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    table_id UUID NOT NULL REFERENCES dining_table(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    join_code VARCHAR(6),
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    close_reason TEXT CHECK (close_reason IN ('SETTLED', 'EMPTY', 'EXPIRED_EMPTY', 'FORCED')),
    opened_by_type TEXT NOT NULL CHECK (opened_by_type IN ('CUSTOMER', 'STAFF')),
    opened_by_user_id UUID,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Garantiza que una mesa tenga como máximo una sesión abierta de forma concurrente.
CREATE UNIQUE INDEX ux_one_open_session_per_table
ON table_session (table_id)
WHERE status = 'OPEN';

CREATE TABLE session_participant (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES table_session(id) ON DELETE CASCADE,
    user_id UUID,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 30),
    role TEXT NOT NULL CHECK (role IN ('HOST', 'MEMBER')),
    token_hash TEXT NOT NULL UNIQUE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Semilla de mesas demo para Somphie Zúrich (Restaurante ID: 6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68)
INSERT INTO dining_table (id, restaurant_id, label, table_code, qr_version, capacity, active)
VALUES
    ('a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'Mesa 1', 'TBL-001', 1, 4, TRUE),
    ('b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'Mesa 2', 'TBL-002', 1, 2, TRUE),
    ('c3d4e5f6-a7b8-9c0d-1e2f-3a4b5c6d7e8f', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'Mesa 3', 'TBL-003', 1, 6, TRUE)
ON CONFLICT (table_code) DO NOTHING;
