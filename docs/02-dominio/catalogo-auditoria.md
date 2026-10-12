# Catálogo de acciones de auditoría

> Vocabulario **cerrado** de `audit_event.action` (D-151). Los códigos de la sección 2 salen **textualmente** de `estados-y-transiciones.md` y `reglas-negocio.md`. Los de la sección 3 los exige `RN-AUD-01` pero ningún documento los nombraba: son **propuestos (🟡)** hasta que el dueño los confirme.
> Para agregar una acción: primero se agrega aquí (con su origen), después se usa en el código. Una acción que no está en este catálogo no se puede escribir.

## 1. Reglas generales (vienen de RN-AUD)

- **Append-only** (RN-AUD-02): la app no tiene `UPDATE/DELETE` sobre la tabla.
- **Misma transacción** que la acción auditada (RN-AUD-05): si la acción se revierte, el registro también.
- **Campos** (RN-AUD-03): `occurredAt`, `actorType` (`USER` / `PARTICIPANT` / `SYSTEM`), `actorId`, `action`, `targetType`, `targetId`, `reason?`, `beforeSummary?`, `afterSummary?`, `traceId`.
- **Nunca** se guardan contraseñas, tokens ni datos personales innecesarios (RN-AUD-04). Los `*Summary` son resúmenes mínimos (estado, importes, códigos), no copias de entidades.
- `reason` es obligatorio donde la fila lo indica (el motivo ya es obligatorio en la regla de negocio).

## 2. Acciones definidas en los documentos de dominio

| Código | Actor | Cuándo se escribe | `targetType` | Datos | Origen |
|---|---|---|---|---|---|
| `SESSION_OPENED` | `PARTICIPANT` (QR) / `USER` (personal) | Se abre una sesión | `SESSION` | `after`: quién abrió (QR o personal), mesa | estados §1 |
| `SESSION_CLOSED` | `USER` | Cierre normal (`SETTLED` o `EMPTY`) | `SESSION` | `after`: `closeReason` | estados §1 |
| `SESSION_EXPIRED` | `SYSTEM` | Expira vacía (`EXPIRED_EMPTY`) | `SESSION` | — | estados §1, RN-SES-08 |
| `SESSION_FORCE_CLOSED` | `USER` | Cierre forzado | `SESSION` | **`reason` obligatorio**; `after`: saldo no cobrado si quedó saldo > 0 | estados §1, RN-SES-06 |
| `ORDER_CREATED` | `PARTICIPANT` / `USER` | Se crea un pedido (también el tomado por el personal, que nace `ACCEPTED`) | `ORDER` | `after`: estado inicial, total | estados §2, RN-ORD-18 |
| `ORDER_ACCEPTED` | `USER` | Se acepta un pedido | `ORDER` | `after`: cantidad de ítems excluidos | estados §2 |
| `ORDER_REJECTED` | `USER` | Se rechaza un pedido | `ORDER` | **`reason`**: `reasonCode` (+ texto) | estados §2, RN-ORD-09 |
| `ORDER_CANCELLED` | `PARTICIPANT` / `USER` | Se cancela estando `PENDING_REVIEW` | `ORDER` | — | estados §2, RN-ORD-11 |
| `ORDER_EXPIRED` | `SYSTEM` | `PENDING_REVIEW` vence por TTL | `ORDER` | — | estados §2, RN-ORD-12 |
| `ITEM_EXCLUDED` | `USER` | Se excluye un ítem al aceptar | `ORDER_ITEM` | **`reason` obligatorio** | estados §3, RN-ORD-10 |
| `ITEM_STATUS_CHANGED` | `USER` | `start`, `ready`, `deliver` | `ORDER_ITEM` | `before` / `after`: estado del ítem | estados §3 |
| `ITEM_STATUS_REVERTED` | `USER` | `undo` | `ORDER_ITEM` | `before` / `after`: estado del ítem | estados §3, RN-PRE-09 |
| `ITEM_CANCELLED` | `USER` | Se cancela un ítem aceptado | `ORDER_ITEM` | **`reason` obligatorio** | estados §3, RN-PRE-07 |
| `PAYMENT_CREATED` | `PARTICIPANT` / `USER` | Pago `SUCCEEDED` (online o efectivo) | `PAYMENT` | `after`: `amountMinor`, `tipMinor`, `method` | estados §5 |
| `PAYMENT_FAILED` | `PARTICIPANT` | Pago simulado rechazado (`FAILED`) | `PAYMENT` | `after`: `amountMinor` | estados §5, RN-BIL-05 |
| `PAYMENT_VOIDED` | `USER` | Se anula un pago (y su propina) | `PAYMENT` | **`reason` obligatorio** | estados §5, RN-BIL-06 |
| `REFUND_CREATED` | `USER` | Reembolso manual, **o automático del cierre forzado** (actor = quien forzó el cierre) | `PAYMENT` | **`reason` obligatorio**; en el automático, marcado "automático por cierre forzado" | estados §1 y §5, RN-BIL-07, RN-SES-06 |
| `ADJUSTMENT_APPLIED` | `USER` | Se aplica un ajuste | `ADJUSTMENT` | **`reason`**: `reasonCode`; `after`: `kind`, importe | estados §8.2, RN-ADJ-02 |
| `ADJUSTMENT_VOIDED` | `USER` | Se anula un ajuste (incluida una cortesía al cancelar su ítem, RN-ADJ-08) | `ADJUSTMENT` | **`reason` obligatorio** | estados §8.2, RN-ADJ-07 |

## 3. Acciones propuestas 🟡 (las exige `RN-AUD-01`, falta confirmarlas)

Código mínimo por categoría; si más adelante hace falta más detalle se puede dividir, sin cambiar los códigos de la sección 2.

| Código | Actor | Cuándo | `targetType` | Datos | `RN-AUD-01` dice |
|---|---|---|---|---|---|
| `PARTICIPANT_REVOKED` | `PARTICIPANT` (HOST) / `USER` | Se revoca a un participante | `PARTICIPANT` | `reason` si se informa | "revocación de participantes" |
| `CATALOG_CHANGED` | `USER` | Alta, edición, archivo o disponibilidad de categoría, producto, grupo u opción | `CATEGORY` / `PRODUCT` / `OPTION_GROUP` / `OPTION` | `before` / `after` de los campos cambiados | "cambios de catálogo" |
| `USER_CHANGED` | `USER` | Alta o cambio de usuario, roles, estaciones o habilitación | `USER` | `before` / `after` (sin credenciales) | "cambios de usuarios/roles/estaciones" |
| `TABLE_QR_REGENERATED` | `USER` | Se regenera el `tableCode` (RN-ACC-03) | `TABLE` | — | "regeneración de QR" |
| `SETTINGS_CHANGED` | `USER` | Cambian los ajustes del restaurante | `SETTINGS` | `before` / `after` de las claves cambiadas | "cambios de ajustes" |
| `LOGIN_FAILED_REPEATED` | `SYSTEM` | Se alcanza el umbral de fallos (RN-IDN-04) | `USER` | cuenta y origen, nunca la contraseña | "logins fallidos reiterados" |

## 4. Lo que NO se audita (hoy)

`RN-AUD-01` no lo incluye y no se asume: llamados al mozo, rotación del `joinCode`, y consultas de lectura. Si el dueño quiere auditarlos, se decide y se agrega aquí primero.

## 5. Verificación (criterio de cierre del Paso 6)

Todo código con la columna "Auditoría" de `estados-y-transiciones.md` (§1, §2, §3, §5, §8.2) figura en la sección 2. Las categorías de `RN-AUD-01` sin código previo están en la sección 3.
