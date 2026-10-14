# Modelo de datos (PostgreSQL)

> Describe tablas, restricciones e índices **indicativos**. El DDL real vive en migraciones Flyway (`backend/src/main/resources/db/migration`). Una migración aplicada **nunca se edita**: se agrega una nueva.

## 1. Convenciones
- Nombres `snake_case`, tablas en singular. Evitar palabras reservadas: `dining_table`, `customer_order`, `app_user`.
- PK `id uuid`. Toda tabla de negocio lleva `restaurant_id uuid not null` (D-003).
- Dinero: `bigint` en centavos con `CHECK`. Fechas: `timestamptz` UTC. Enums como `text` + `CHECK` (más fáciles de migrar que enums nativos).
- Auditoría de fila: `created_at`, `updated_at`; `version bigint` donde haya edición concurrente.
- Migraciones: `V{n}__{descripcion}.sql`; datos demo en `db/seed-dev` (solo perfil `dev`/`demo`).

## 2. Tablas

| Tabla | Columnas principales | Restricciones / índices clave |
|---|---|---|
| `restaurant` | id, name, timezone, currency | — |
| `restaurant_settings` | restaurant_id (PK/FK), opening_hours jsonb, business_day_cutoff, límites y TTLs, require_staff_to_open_session, receipt_legend | 1 por restaurante |
| `app_user` | id, restaurant_id, email, password_hash, display_name, status, token_version, must_change_password, email_verified_at | `UNIQUE(lower(email))` |
| `role`, `permission`, `role_permission`, `user_role` | códigos y relaciones | `role.code` único; semilla `OWNER/WAITER/BAR/KITCHEN` |
| `station` | id, restaurant_id, code, name | `UNIQUE(restaurant_id, code)` |
| `user_station` | user_id, station_id | PK compuesta |
| `refresh_token` | id, user_id, family_id, token_hash, expires_at, used_at, revoked_at | índice por `token_hash`, `family_id` |
| `category` | id, restaurant_id, name, sort_order, active | `UNIQUE(restaurant_id, name)` |
| `product` | id, restaurant_id, category_id, station_id, name, description, price_minor, available, active, allergens text[], image_url, image_alt, sort_order, version | `CHECK(price_minor > 0)`; `UNIQUE(category_id, lower(name)) WHERE active` |
| `dining_table` | id, restaurant_id, label, table_code, qr_version, capacity, active | `UNIQUE(table_code)`; `UNIQUE(restaurant_id, label)` |
| `table_session` | id, restaurant_id, table_id, status, join_code, opened_at, closed_at, close_reason, closed_by, last_activity_at, opened_by_type | **índice único parcial** `(table_id) WHERE status='OPEN'` |
| `session_participant` | id, session_id, display_name, role, token_hash, user_id?, revoked_at, created_at | `UNIQUE(token_hash)`; un solo `HOST` por sesión (índice parcial) |
| `customer_order` | id, restaurant_id, session_id, display_code, business_date, status, note, created_by_participant_id?, created_by_user_id?, decided_by, decided_at, rejection_reason_code, rejection_reason_text, version, created_at | `version` inicia en 1 y cambia una vez por mutación; las actualizaciones comparan la versión anterior. `UNIQUE(restaurant_id, business_date, display_code)`; índices por sesión y estado |
| `order_item` | id, order_id, session_id (denormalizado), product_id, product_name, product_name_i18n jsonb, unit_price_minor, options_delta_minor, quantity, line_total_minor, note, station_id, status, options_snapshot jsonb, cancellation_reason, queued_at, started_at, ready_at, delivered_at, cancelled_at | Snapshot de opciones incluye IDs, nombres de grupo/opción y delta; los datos históricos ausentes quedan vacíos, sin reconstruirlos desde el catálogo. `queued_at` solo se registra en la transición del ítem; los registros anteriores quedan `NULL` porque `accepted_at` no prueba cuándo entró cada ítem en cola. Índice FIFO por estación/estado/cola |
| `order_counter` | restaurant_id, business_date, last_value | PK `(restaurant_id, business_date)` |
| `payment` | id, restaurant_id, session_id, kind, method, status, amount_minor, payer_participant_id?, payer_label?, reason, created_by_type/id, voided_at, idempotency_key | `CHECK(amount_minor > 0)`; `UNIQUE(session_id, idempotency_key)`; índice `(session_id)` |
| `idempotency_record` | id, scope (actor+endpoint), key, request_hash, response_status, response_body jsonb, created_at, expires_at | `UNIQUE(scope, key)`; limpieza a 24 h |
| `audit_event` | id, restaurant_id, occurred_at, actor_type, actor_id, action, target_type, target_id, reason, before_summary jsonb, after_summary jsonb, trace_id | **Append-only**: sin `UPDATE/DELETE` para el rol de la app; índices `(occurred_at)`, `(target_type,target_id)` |
| `event_log` | id bigserial, restaurant_id, type, aggregate_type, aggregate_id, scope_session_id?, version, payload jsonb, created_at | Retención 48 h; índice `(id)` para `Last-Event-ID` |
| `feedback` (F9) | id, session_id, participant_id, rating, comment | `UNIQUE(session_id, participant_id)` |

> `event_log` describe el modelo objetivo de tiempo real; no existe todavía en las migraciones. La tabla implementada es `audit_event` (V7), con otro propósito y sin cursor/outbox SSE. No tratarlas como intercambiables. La propuesta de WP-ARQ-60 conserva el log durable en PostgreSQL y usa streams de solo lectura; para no saltar commits tardíos, propone serializar la asignación del cursor por restaurante. Esa contención y el envelope para aggregates sin `version` requieren aprobación/medición antes de fijar DDL.

## 3. Restricciones críticas (en SQL)

```sql
-- Una sola sesión abierta por mesa (RN-SES-01)
CREATE UNIQUE INDEX ux_one_open_session_per_table
  ON table_session (table_id) WHERE status = 'OPEN';

-- Un solo host por sesión (RN-ACC-15). Una sesión abierta por el personal puede no tener HOST
-- hasta que alguien se una (D-143). Ver §7.5 para el patrón de unión.
CREATE UNIQUE INDEX ux_one_host_per_session
  ON session_participant (session_id) WHERE role = 'HOST';

-- Idempotencia de pagos a nivel de datos
ALTER TABLE payment ADD CONSTRAINT ux_payment_idem UNIQUE (session_id, idempotency_key);

-- Dinero siempre válido (nota: el CHECK de `payment` fue reemplazado en v0.2, ver §7.2)
ALTER TABLE payment ADD CONSTRAINT ck_payment_amount CHECK (amount_minor > 0);
ALTER TABLE order_item ADD CONSTRAINT ck_item_qty CHECK (quantity BETWEEN 1 AND 99);
ALTER TABLE product ADD CONSTRAINT ck_product_price CHECK (price_minor > 0);

-- Estados válidos (los enums del contrato y de la DB coinciden)
ALTER TABLE customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
 ('PENDING_REVIEW','REJECTED','CANCELLED','EXPIRED','ACCEPTED','IN_PREPARATION','READY','DELIVERED'));
ALTER TABLE order_item ADD CONSTRAINT ck_item_status CHECK (status IN
 ('PENDING','QUEUED','IN_PREPARATION','READY','DELIVERED','CANCELLED'));

-- Número de pedido por día de negocio, atómico
-- INSERT … ON CONFLICT (restaurant_id, business_date)
--   DO UPDATE SET last_value = order_counter.last_value + 1 RETURNING last_value;

-- Audit append-only: revocar permisos al usuario de la aplicación
-- REVOKE UPDATE, DELETE ON audit_event FROM app_user_role;
```

## 4. Consultas clave (para planificar índices)

| Consulta | Usa |
|---|---|
| Cola de entrantes: `status='PENDING_REVIEW' ORDER BY created_at` | `customer_order(status, created_at)` |
| Cola de estación: `station_id=? AND status IN ('QUEUED','IN_PREPARATION') ORDER BY queued_at` | `order_item(station_id,status,queued_at)` |
| Cuenta de sesión (cargos) | `order_item` por `session_id` + `status<>'CANCELLED'` y pedido en estados facturables |
| Saldo de pagos | `payment(session_id)` con `status='SUCCEEDED'` |
| Tablero | `table_session` abiertas + agregados por sesión |
| Job de expiración de pedidos | `(status, created_at)` |
| Reportes | vistas SQL de solo lectura propiedad de `reporting` |

> **Cargos facturables** (SQL conceptual): ítems con `status <> 'CANCELLED'` cuyo pedido está en `('ACCEPTED','IN_PREPARATION','READY','DELIVERED')`. Esto se encapsula en **una sola** consulta/función (`billableChargesForSession`) usada por cuenta, pagos y cierre, para que nunca haya dos definiciones distintas.

## 5. Retención de datos (🟡 Q-07)
| Dato | Propuesta |
|---|---|
| `idempotency_record` | 24 h |
| `event_log` | 48 h |
| Tokens de invitado | Hasta 24 h post-cierre; luego se pueden purgar |
| Participantes invitados (nombres) | Conservar con la sesión (historial); anonimizar tras N meses (a definir) |
| `audit_event` | Indefinido en demo; política a definir |
| Emails de clientes registrados | Mientras la cuenta exista; borrado a pedido |

## 6. Semillas
- **Obligatorias (todos los entornos):** permisos, roles de sistema, estaciones `BAR` y `KITCHEN`, restaurante y settings por defecto.
- **Demo/dev:** categorías y productos de ejemplo de Somphie Zúrich, 8–10 mesas con `tableCode`, usuarios de prueba (`owner@`, `waiter@`, `bar@`, `kitchen@` con contraseñas **solo de demo** documentadas en `entorno-local.md`).

## 7. Cambios de la v0.2

### 7.1 Tablas nuevas
| Tabla | Columnas principales | Restricciones / índices |
|---|---|---|
| `option_group` | id, restaurant_id, name, selection_type (`SINGLE`/`MULTIPLE`), required, min_selections, max_selections, sort_order, active, version | `CHECK(min_selections <= max_selections)` |
| `option` | id, group_id, name, price_delta_minor, available, active, is_default, sort_order, version | `CHECK(price_delta_minor >= 0)` |
| `product_option_group` | product_id, group_id, sort_order | PK `(product_id, group_id)` |
| `category_translation`, `product_translation`, `option_group_translation`, `option_translation` | entity_id, locale, name, (description) | PK `(entity_id, locale)` |
| `bill_adjustment` | id, restaurant_id, session_id, kind, order_item_id?, percent_bp?, amount_minor, reason_code, reason_text, status, created_by, `limit_exempt`, created_at, voided_by?, voided_at?, void_reason?, idempotency_key | `CHECK(amount_minor > 0)`; `UNIQUE(session_id, idempotency_key)`; índice `(session_id, status)` y único parcial por ítem con cortesía activa |
| `waiter_call` | id, session_id, participant_id, reason, text, status, created_at, acknowledged_at?, resolved_at?, expires_at | `CHECK` de motivo/estado/ciclo de vida; índice único parcial `(session_id, reason) WHERE status IN ('OPEN','ACKNOWLEDGED')`; índices de sesión, expiración y cooldown |

### 7.2 Tablas modificadas
| Tabla | Cambio |
|---|---|
| `product` | `image_url` → `image_path` + `image_version`; alérgenos limitados a la lista de D-137 |
| `order_item` | + `base_price_minor`, `name_i18n jsonb`; `options_snapshot` deja de estar reservado y pasa a usarse |
| `payment` | + `tip_minor bigint not null default 0`. Se **reemplaza** `CHECK(amount_minor > 0)` por `CHECK(amount_minor >= 0 AND tip_minor >= 0 AND amount_minor + tip_minor > 0)` y se agrega `CHECK(kind = 'PAYMENT' OR tip_minor = 0)` |
| `restaurant_settings` | + `max_tip_percent` (100), `max_staff_discount_percent` (**15**, D-142), `max_staff_adjustment_minor` (nullable: sin tope por defecto, D-142), `waiter_call_cooldown_sec` (60), `default_locale` (`es-AR`), `supported_locales` |

### 7.3 Consulta única de cuenta (actualizada)
`billableChargesForSession` sigue siendo **la única** definición de cargos. La cuenta deriva los ajustes `APPLIED`, pagos y reembolsos `SUCCEEDED` de sus tablas persistidas: `paidNet = pagos − reembolsos`; `balance = charges − ajustes − paidNet`. Las propinas solo suman desde pagos exitosos y no cambian el saldo. `limit_exempt` conserva si el ajuste fue aplicado con `adjustment.apply_any`, para que no consuma el límite acumulado del Mozo.

### 7.4 Retención (D-135)
Nombres de invitados: anonimizar a los 12 meses (🟡). `waiter_call`: 30 días. El resto sin cambios.

### 7.5 Cambios del Paso 3 (2026-10-11)

**HOST en sesiones abiertas por el personal (D-143, RN-ACC-14/15).** `table_session.opened_by_type` indica quién abrió la sesión. Si la abrió el personal, no existe fila `HOST` hasta que alguien se une. Patrón de unión (indicativo; todo dentro de **una** transacción):

```sql
-- 1) Intentar ser HOST. Si ya hay uno, no inserta nada y no falla.
INSERT INTO session_participant (id, session_id, display_name, role, token_hash, created_at)
VALUES (:id, :sessionId, :name, 'HOST', :tokenHash, now())
ON CONFLICT (session_id) WHERE role = 'HOST' DO NOTHING;
-- 2) Si el paso 1 insertó 0 filas (el HOST ya existía), insertar el mismo participante con role = 'MEMBER'.
```

Por qué así: **no** se consulta "¿hay HOST?" para insertar después. Dos transacciones podrían consultar a la vez, ver "no hay" y ambas intentar ser HOST (condición de carrera). Se deja que la base decida con el índice único parcial `ux_one_host_per_session`: la segunda transacción espera el commit de la primera y su `INSERT` no hace nada.

**Pago mínimo (D-158).** Sin cambios de esquema: `min(minPaymentMinor, balance)` se valida en la aplicación.

**Cierre forzado (D-159).** Sin columnas nuevas. El reembolso automático es una fila `payment` con `kind = 'REFUND'` cuyo `reason` lo identifica como "cierre forzado automático". El saldo no cobrado no se guarda: se calcula con la consulta de cuenta (`charges − adjustments − paidNet`) y se copia a `audit_event.after_summary` al cerrar.
