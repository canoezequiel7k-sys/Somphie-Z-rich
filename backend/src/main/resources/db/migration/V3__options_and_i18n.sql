-- Migración V3: Grupos de opciones, opciones de productos y soporte de localización i18n.

CREATE TABLE option_group (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    name_i18n JSONB,
    selection_type TEXT NOT NULL CHECK (selection_type IN ('SINGLE', 'MULTIPLE')),
    required BOOLEAN NOT NULL DEFAULT FALSE,
    min_selections INTEGER NOT NULL DEFAULT 0 CHECK (min_selections >= 0),
    max_selections INTEGER NOT NULL DEFAULT 1 CHECK (max_selections > 0),
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE product_option_group (
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES option_group(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, group_id)
);

CREATE TABLE product_option (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES option_group(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    name_i18n JSONB,
    price_delta_minor BIGINT NOT NULL DEFAULT 0 CHECK (price_delta_minor >= 0),
    available BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

-- Semilla de opciones para el producto "Flat white" (id: 9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7)
-- 1. Grupo: Tamaño (SINGLE, required = true)
INSERT INTO option_group (id, restaurant_id, name, name_i18n, selection_type, required, min_selections, max_selections, sort_order)
VALUES (
    '3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6',
    '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68',
    'Tamaño',
    '{"en": "Size", "es-AR": "Tamaño"}'::jsonb,
    'SINGLE',
    TRUE,
    1,
    1,
    1
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_option_group (product_id, group_id, sort_order)
VALUES ('9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7', '3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6', 1)
ON CONFLICT (product_id, group_id) DO NOTHING;

INSERT INTO product_option (id, group_id, name, name_i18n, price_delta_minor, available, is_default, sort_order)
VALUES
(
    'd4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70',
    '3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6',
    'Chico',
    '{"en": "Small", "es-AR": "Chico"}'::jsonb,
    0,
    TRUE,
    TRUE,
    1
),
(
    'e5f6a7b8-c9d0-4e1f-9a2b-3c4d5e6f7081',
    '3f9e1d2c-4b5a-4687-9a0b-c1d2e3f4a5b6',
    'Grande',
    '{"en": "Large", "es-AR": "Grande"}'::jsonb,
    80000,
    TRUE,
    FALSE,
    2
)
ON CONFLICT (id) DO NOTHING;

-- 2. Grupo: Leche (SINGLE, required = false)
INSERT INTO option_group (id, restaurant_id, name, name_i18n, selection_type, required, min_selections, max_selections, sort_order)
VALUES (
    '4a0f2e3d-5c6b-4798-8b1c-d2e3f4a5b6c7',
    '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68',
    'Leche',
    '{"en": "Milk", "es-AR": "Leche"}'::jsonb,
    'SINGLE',
    FALSE,
    0,
    1,
    2
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_option_group (product_id, group_id, sort_order)
VALUES ('9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7', '4a0f2e3d-5c6b-4798-8b1c-d2e3f4a5b6c7', 2)
ON CONFLICT (product_id, group_id) DO NOTHING;

INSERT INTO product_option (id, group_id, name, name_i18n, price_delta_minor, available, is_default, sort_order)
VALUES
(
    'f6a7b8c9-d0e1-4f2a-8b3c-4d5e6f708192',
    '4a0f2e3d-5c6b-4798-8b1c-d2e3f4a5b6c7',
    'Avena',
    '{"en": "Oat milk", "es-AR": "Avena"}'::jsonb,
    60000,
    TRUE,
    FALSE,
    1
)
ON CONFLICT (id) DO NOTHING;
