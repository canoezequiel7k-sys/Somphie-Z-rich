-- Migración V2: Módulo de catálogo (categorías, subcategorías, productos) y semilla demo.

CREATE TABLE category (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    name_i18n JSONB,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE subcategory (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    name_i18n JSONB,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    subcategory_id UUID NOT NULL REFERENCES subcategory(id) ON DELETE CASCADE,
    station_id UUID NOT NULL REFERENCES station(id),
    name TEXT NOT NULL,
    name_i18n JSONB,
    description TEXT,
    description_i18n JSONB,
    price_minor BIGINT NOT NULL CHECK (price_minor >= 0),
    allergens TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    image_path TEXT,
    image_version INTEGER NOT NULL DEFAULT 1,
    image_alt TEXT,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

-- Semilla de catálogo para el restaurante Somphie Zúrich
-- Restaurant ID: 6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68
-- Stations: BAR, KITCHEN (creadas en V1)

-- 1. Categoría: Café y Bebidas (Estación BAR)
INSERT INTO category (id, restaurant_id, name, sort_order)
VALUES ('5c0f2a7e-3d41-4b8a-9a52-7e1d6f0a2b11', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'Café y Bebidas', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO subcategory (id, category_id, name, sort_order)
VALUES ('11111111-3d41-4b8a-9a52-7e1d6f0a2b11', '5c0f2a7e-3d41-4b8a-9a52-7e1d6f0a2b11', 'Cafetería', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO product (id, subcategory_id, station_id, name, description, price_minor, allergens, image_path, image_version, image_alt, available, sort_order)
VALUES (
    '9a2b4c6d-8e0f-4a1b-b3c5-d7e9f1a3b5c7',
    '11111111-3d41-4b8a-9a52-7e1d6f0a2b11',
    (SELECT id FROM station WHERE code = 'BAR' LIMIT 1),
    'Flat white',
    'Doble espresso con leche texturizada.',
    485000,
    ARRAY['MILK'],
    'menu/flat-white.webp',
    3,
    'Flat white en taza de cerámica',
    TRUE,
    1
)
ON CONFLICT (id) DO NOTHING;

-- 2. Categoría: Panadería y Pastelería (Estación KITCHEN)
INSERT INTO category (id, restaurant_id, name, sort_order)
VALUES ('6d1f3b8f-4e52-5c9b-0b63-8f2e701b3c22', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'Panadería y Pastelería', 2)
ON CONFLICT (id) DO NOTHING;

INSERT INTO subcategory (id, category_id, name, sort_order)
VALUES ('22222222-4e52-5c9b-0b63-8f2e701b3c22', '6d1f3b8f-4e52-5c9b-0b63-8f2e701b3c22', 'Vienesa', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO product (id, subcategory_id, station_id, name, description, price_minor, allergens, image_path, image_version, image_alt, available, sort_order)
VALUES (
    '8b3c5d7e-9f1a-3b5c-7d9e-1f3a5b7c9d1e',
    '22222222-4e52-5c9b-0b63-8f2e701b3c22',
    (SELECT id FROM station WHERE code = 'KITCHEN' LIMIT 1),
    'Medialuna de manteca',
    'Recién horneada, dorada y crujiente.',
    120000,
    ARRAY['GLUTEN', 'MILK', 'EGG'],
    'menu/medialuna.webp',
    1,
    'Medialuna de manteca en plato',
    TRUE,
    1
)
ON CONFLICT (id) DO NOTHING;
