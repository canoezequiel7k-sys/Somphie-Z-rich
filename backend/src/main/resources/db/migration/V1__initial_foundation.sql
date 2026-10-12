-- Crea el restaurante inicial con los datos acordados en D-101 y vision.md.
CREATE TABLE restaurant (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    timezone TEXT NOT NULL,
    currency CHAR(3) NOT NULL
);

-- Guarda una única configuración operativa por restaurante, con los defaults de D-106 y v0.2.
CREATE TABLE restaurant_settings (
    restaurant_id UUID PRIMARY KEY REFERENCES restaurant(id) ON DELETE CASCADE,
    opening_hours JSONB,
    business_day_cutoff TIME NOT NULL DEFAULT TIME '04:00',
    max_lines_per_order INTEGER NOT NULL DEFAULT 30 CHECK (max_lines_per_order > 0),
    max_qty_per_line INTEGER NOT NULL DEFAULT 20 CHECK (max_qty_per_line > 0),
    max_note_length INTEGER NOT NULL DEFAULT 140 CHECK (max_note_length >= 0),
    max_pending_orders_per_session INTEGER NOT NULL DEFAULT 3 CHECK (max_pending_orders_per_session > 0),
    max_participants INTEGER NOT NULL DEFAULT 12 CHECK (max_participants > 0),
    order_pending_ttl_min INTEGER NOT NULL DEFAULT 15 CHECK (order_pending_ttl_min > 0),
    session_idle_ttl_min INTEGER NOT NULL DEFAULT 30 CHECK (session_idle_ttl_min > 0),
    delivered_undo_window_min INTEGER NOT NULL DEFAULT 5 CHECK (delivered_undo_window_min >= 0),
    closed_session_read_hours INTEGER NOT NULL DEFAULT 24 CHECK (closed_session_read_hours >= 0),
    min_payment_minor BIGINT NOT NULL DEFAULT 100 CHECK (min_payment_minor > 0),
    delayed_item_sla_min INTEGER NOT NULL DEFAULT 10 CHECK (delayed_item_sla_min > 0),
    require_staff_to_open_session BOOLEAN NOT NULL DEFAULT FALSE,
    max_tip_percent INTEGER NOT NULL DEFAULT 100 CHECK (max_tip_percent >= 0),
    max_staff_discount_percent INTEGER NOT NULL DEFAULT 15 CHECK (max_staff_discount_percent >= 0),
    max_staff_adjustment_minor BIGINT CHECK (max_staff_adjustment_minor IS NULL OR max_staff_adjustment_minor >= 0),
    waiter_call_cooldown_sec INTEGER NOT NULL DEFAULT 60 CHECK (waiter_call_cooldown_sec > 0),
    default_locale TEXT NOT NULL DEFAULT 'es-AR',
    supported_locales TEXT[] NOT NULL DEFAULT ARRAY['es-AR', 'en'],
    receipt_legend TEXT
);

-- Mantiene los roles de sistema como datos para permitir evolucionarlos sin cambiar el esquema.
CREATE TABLE role (
    code TEXT PRIMARY KEY,
    is_system BOOLEAN NOT NULL DEFAULT TRUE
);

-- Registra permisos estables que la aplicación asignará a roles según la matriz aprobada.
CREATE TABLE permission (
    code TEXT PRIMARY KEY,
    is_system BOOLEAN NOT NULL DEFAULT TRUE
);

-- Evita referencias a códigos de roles o permisos que no existen.
CREATE TABLE role_permission (
    role_code TEXT NOT NULL REFERENCES role(code) ON DELETE CASCADE,
    permission_code TEXT NOT NULL REFERENCES permission(code) ON DELETE CASCADE,
    PRIMARY KEY (role_code, permission_code)
);

-- Crea las estaciones base exigidas para todos los entornos.
CREATE TABLE station (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    code TEXT NOT NULL CHECK (code IN ('BAR', 'KITCHEN')),
    name TEXT NOT NULL,
    UNIQUE (restaurant_id, code)
);

-- Inserta una identidad inicial estable para que el seed sobreviva a reinicios y reprocesos.
INSERT INTO restaurant (id, name, timezone, currency)
VALUES ('6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'Somphie Zúrich', 'America/Argentina/Buenos_Aires', 'ARS')
ON CONFLICT (id) DO NOTHING;

-- Crea la configuración inicial con valores respaldados por reglas-negocio.md y modelo-datos.md.
INSERT INTO restaurant_settings (restaurant_id)
VALUES ('6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68')
ON CONFLICT (restaurant_id) DO NOTHING;

-- Publica los cuatro roles del MVP y deja el catálogo ampliable por migraciones futuras.
INSERT INTO role (code) VALUES ('OWNER'), ('WAITER'), ('BAR'), ('KITCHEN')
ON CONFLICT (code) DO NOTHING;

-- Siembra permisos del contrato y del catálogo de actores; `role.manage` queda sin asignar en el MVP.
INSERT INTO permission (code) VALUES
    ('menu.read'), ('session.open'), ('session.join'), ('order.create'), ('order.cancel_own'),
    ('bill.read'), ('payment.create'), ('participant.manage'), ('order.read'), ('order.accept'),
    ('order.reject'), ('order.cancel'), ('order.create_on_behalf'), ('item.prepare'), ('item.deliver'),
    ('item.undo'), ('session.read'), ('session.open_manual'), ('session.close'), ('session.force_close'),
    ('participant.revoke'), ('payment.register_cash'), ('payment.void'), ('payment.refund'),
    ('availability.toggle'), ('table.read'), ('catalog.manage'), ('table.manage'), ('user.manage'),
    ('role.manage'), ('report.read'), ('report.export'), ('audit.read'), ('settings.manage'),
    ('adjustment.apply'), ('adjustment.apply_any'), ('adjustment.void'), ('waiter_call.create'),
    ('waiter_call.handle')
ON CONFLICT (code) DO NOTHING;

-- Traduce la matriz rol × permiso de actores-y-roles.md en asignaciones persistentes.
INSERT INTO role_permission (role_code, permission_code)
SELECT grants.role_code, grants.permission_code
FROM (VALUES
    ('OWNER', 'menu.read'), ('OWNER', 'order.read'), ('OWNER', 'order.accept'),
    ('OWNER', 'order.reject'), ('OWNER', 'order.cancel'), ('OWNER', 'order.create_on_behalf'),
    ('OWNER', 'item.prepare'), ('OWNER', 'item.deliver'), ('OWNER', 'item.undo'),
    ('OWNER', 'session.read'), ('OWNER', 'session.open_manual'), ('OWNER', 'session.close'),
    ('OWNER', 'session.force_close'), ('OWNER', 'participant.revoke'),
    ('OWNER', 'payment.register_cash'), ('OWNER', 'payment.void'), ('OWNER', 'payment.refund'),
    ('OWNER', 'availability.toggle'), ('OWNER', 'table.read'), ('OWNER', 'catalog.manage'),
    ('OWNER', 'table.manage'), ('OWNER', 'user.manage'), ('OWNER', 'report.read'),
    ('OWNER', 'report.export'), ('OWNER', 'audit.read'), ('OWNER', 'settings.manage'),
    ('OWNER', 'adjustment.apply'), ('OWNER', 'adjustment.apply_any'), ('OWNER', 'adjustment.void'),
    ('OWNER', 'waiter_call.handle'),
    ('WAITER', 'menu.read'), ('WAITER', 'order.read'), ('WAITER', 'order.accept'),
    ('WAITER', 'order.reject'), ('WAITER', 'order.cancel'), ('WAITER', 'order.create_on_behalf'),
    ('WAITER', 'item.deliver'), ('WAITER', 'item.undo'), ('WAITER', 'session.read'),
    ('WAITER', 'session.open_manual'), ('WAITER', 'session.close'), ('WAITER', 'participant.revoke'),
    ('WAITER', 'payment.register_cash'), ('WAITER', 'payment.void'), ('WAITER', 'payment.refund'),
    ('WAITER', 'availability.toggle'), ('WAITER', 'table.read'), ('WAITER', 'adjustment.apply'),
    ('WAITER', 'adjustment.void'), ('WAITER', 'waiter_call.handle'),
    ('BAR', 'menu.read'), ('BAR', 'order.read'), ('BAR', 'order.accept'), ('BAR', 'order.reject'),
    ('BAR', 'order.cancel'), ('BAR', 'order.create_on_behalf'), ('BAR', 'item.prepare'),
    ('BAR', 'item.deliver'), ('BAR', 'item.undo'), ('BAR', 'session.read'),
    ('BAR', 'payment.register_cash'), ('BAR', 'availability.toggle'), ('BAR', 'table.read'),
    ('BAR', 'waiter_call.handle'),
    ('KITCHEN', 'menu.read'), ('KITCHEN', 'order.read'), ('KITCHEN', 'order.cancel'),
    ('KITCHEN', 'item.prepare'), ('KITCHEN', 'item.undo'), ('KITCHEN', 'availability.toggle')
) AS grants(role_code, permission_code)
ON CONFLICT (role_code, permission_code) DO NOTHING;

-- Asocia estaciones funcionales a la semilla única del restaurante inicial.
INSERT INTO station (id, restaurant_id, code, name) VALUES
    ('c36f4a29-d2a8-4df5-9b56-31d55935bb01', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'BAR', 'Barra'),
    ('c36f4a29-d2a8-4df5-9b56-31d55935bb02', '6f2d8a41-3b9c-4e75-a1d0-5c7e9b3f2a68', 'KITCHEN', 'Cocina')
ON CONFLICT (restaurant_id, code) DO NOTHING;
