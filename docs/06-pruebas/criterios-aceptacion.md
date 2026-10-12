# Criterios de aceptación

Formato Dado/Cuando/Entonces. Cada criterio `AC-CU-nn-x` es verificable y se traza a pruebas. Se cubren los casos de uso del núcleo; el resto se completa en cada fase siguiendo la misma plantilla (WP-QA).

## 1. Criterios globales (todo caso de uso)
| ID | Criterio |
|---|---|
| AC-G-01 | Toda operación de escritura valida **permiso y alcance** en el servidor (probado con un rol no autorizado) |
| AC-G-02 | Toda respuesta de error usa el formato Problem+JSON con `code` del catálogo y `traceId` |
| AC-G-03 | Toda operación marcada `Idem` es segura ante reintento (misma clave → mismo resultado, un solo efecto) |
| AC-G-04 | Toda acción relevante deja `AuditEvent` en la misma transacción |
| AC-G-05 | Las pantallas implementan cargando/vacío/error/sin conexión/éxito |
| AC-G-06 | Ningún cliente muestra como definitivo un dato que el servidor no confirmó |
| AC-G-07 | Las cadenas de UI están centralizadas (es-AR) y sin HTML inyectado |
| AC-G-08 | Accesibilidad básica: contraste AA, foco, etiquetas |

## 2. Núcleo

### CU-02 Abrir sesión
- **AC-CU-02-a** Dado una mesa activa sin sesión y local abierto, cuando se abre con nombre válido, entonces se crea una sesión `OPEN`, un participante `HOST`, un `joinCode` de 6 caracteres y un token; el tablero muestra la mesa `OCCUPIED`.
- **AC-CU-02-b** Dado una mesa con sesión `OPEN`, cuando otra persona intenta abrir, entonces `409 SESSION_ALREADY_OPEN` y no se crea otra sesión.
- **AC-CU-02-c** Dado dos aperturas simultáneas, entonces existe exactamente una sesión `OPEN` (CL-027).
- **AC-CU-02-d** Dado local cerrado, entonces `RESTAURANT_CLOSED`.

### CU-03 Unirse
- **AC-CU-03-a** Con código correcto se crea un `MEMBER` y token; el nuevo participante ve los pedidos existentes.
- **AC-CU-03-b** Con código incorrecto: `JOIN_CODE_INVALID`; tras 5 fallos, `JOIN_CODE_RATE_LIMITED` (CL-042).
- **AC-CU-03-c** Con sesión llena: `SESSION_FULL`.

### CU-07 Enviar pedido
- **AC-CU-07-a** Dado un carrito válido, entonces se crea un pedido `PENDING_REVIEW` con `displayCode`, ítems `PENDING` y **snapshot** de nombre/precio/estación.
- **AC-CU-07-b** El servidor ignora cualquier precio enviado por el cliente (CL-063).
- **AC-CU-07-c** Si `acknowledgedTotalMinor` ≠ total calculado → `PRICE_CHANGED` con precios vigentes y no se crea el pedido (CL-060).
- **AC-CU-07-d** Producto no disponible → `PRODUCT_UNAVAILABLE` con ids (CL-061).
- **AC-CU-07-e** Reintento con misma clave → mismo pedido y `Idempotent-Replayed: true` (CL-001).
- **AC-CU-07-f** Más de 3 pendientes → `ORDER_PENDING_LIMIT_REACHED` (CL-066).
- **AC-CU-07-g** Sesión cerrada → `SESSION_CLOSED`.
- **AC-CU-07-h** El pedido sobrevive a un reinicio del servidor.

### CU-08/09/10 Seguimiento, cancelación y reenvío
- **AC-CU-08-a** Un participante ve los pedidos de **toda** su sesión y **no** los de otra (404, CL-125).
- **AC-CU-09-a** El autor o el host cancela un pedido `PENDING_REVIEW` → `CANCELLED`; no afecta la cuenta.
- **AC-CU-09-b** Cancelar un pedido ya decidido → `ORDER_NOT_CANCELLABLE`.
- **AC-CU-10-a** Tras rechazo/expiración, el cliente puede reenviar; el pedido original permanece en historial.

### CU-22/23 Aceptar y rechazar
- **AC-CU-22-a** Con `order.accept`, un pedido `PENDING_REVIEW` pasa a `ACCEPTED`; sus ítems a `QUEUED`; aparecen en la cola de su estación; se audita.
- **AC-CU-22-b** Dos aceptaciones simultáneas: una `200`, otra `ORDER_ALREADY_DECIDED` con decisor y hora (CL-020).
- **AC-CU-22-c** Aceptar con ítems excluidos: esos ítems `CANCELLED` y no cobran (CL-072); excluir todos → `ORDER_ALL_ITEMS_EXCLUDED`.
- **AC-CU-22-d** Cocina intentando aceptar → `403` (CL-124).
- **AC-CU-23-a** Rechazar guarda motivo y conserva el pedido en historial; el cliente lo ve con el motivo.

### CU-24/25/26 Preparación y entrega
- **AC-CU-25-a** Solo transiciones válidas; las demás `ITEM_INVALID_TRANSITION` sin cambios (matriz generada).
- **AC-CU-25-b** Un usuario de Barra no puede mover ítems de Cocina (`ITEM_STATION_FORBIDDEN`).
- **AC-CU-25-c** El estado del pedido se recalcula en la misma transacción: no puede quedar `READY` con ítems `QUEUED` (CL-080).
- **AC-CU-26-a** Cuando todos los ítems no cancelados están `DELIVERED`, el pedido pasa a `DELIVERED`.
- **AC-CU-28-a** Deshacer entrega solo dentro de 5 minutos (CL-084).

### CU-11/12 Cuenta y pago
- **AC-CU-11-a** La cuenta suma solo ítems no cancelados de pedidos aceptados, con **precios históricos**; cambiar el catálogo no la altera (CL-114).
- **AC-CU-12-a** Pago parcial válido reduce el saldo exacto; pago total deja saldo 0.
- **AC-CU-12-b** Monto > saldo → `PAYMENT_EXCEEDS_BALANCE` con saldo actual (CL-100).
- **AC-CU-12-c** Dos pagos simultáneos nunca suman más que el saldo (CL-023).
- **AC-CU-12-d** Reintento con misma clave no duplica (CL-005).
- **AC-CU-12-e** En sesión cerrada → `SESSION_CLOSED`.
- **AC-CU-12-f** En ningún momento se piden ni almacenan datos de tarjeta.

### CU-34/35 Cierre
- **AC-CU-34-a** Con pendientes / ítems activos / saldo ≠ 0 el cierre falla con el error correspondiente y **no** modifica nada (CL-109/110/111).
- **AC-CU-34-b** Cierre válido: sesión `CLOSED(SETTLED)`, joinCode invalidado, tokens en solo lectura 24 h, mesa `FREE`.
- **AC-CU-34-c** Después del cierre, pedidos y pagos son rechazados (`SESSION_CLOSED`).
- **AC-CU-34-d** Cerrar dos veces es idempotente (CL-113).
- **AC-CU-35-a** Cierre forzado exige permiso y motivo, cancela pendientes/ítems activos, queda auditado y marcado en el comprobante (CL-112).
- **AC-CU-34-e** Una visita nueva en la misma mesa no hereda pedidos ni saldo (CL-047).

### CU-20 Login del personal
- **AC-CU-20-a** Respuesta idéntica para email inexistente y contraseña errónea (CL-120).
- **AC-CU-20-b** Tras 5 fallos, bloqueo temporal (CL-121).
- **AC-CU-20-c** Refresh rotativo; reutilización revoca la familia (CL-122).
- **AC-CU-20-d** Usuario deshabilitado pierde acceso de inmediato (CL-123).

### CU-40–45 Administración (Fase 8)
- **AC-CU-40-a** Archivar un producto con historia lo oculta del menú y **conserva** pedidos viejos.
- **AC-CU-41-a** Regenerar QR invalida el código anterior (`TABLE_CODE_INVALID`) sin cortar sesiones abiertas.
- **AC-CU-42-a** Imposible dejar el restaurante sin `OWNER` activo.
- **AC-CU-44-a** La auditoría muestra actor, acción, objetivo, motivo y hora; no contiene secretos.

## 3. Criterios no funcionales
| ID | Criterio |
|---|---|
| AC-NF-01 | Presupuestos de rendimiento de `estrategia.md` §5 |
| AC-NF-02 | Sin secretos en el repo (gitleaks limpio) |
| AC-NF-03 | `docker compose up` + README levanta todo en una máquina limpia con Windows |
| AC-NF-04 | Migraciones aplican desde cero y sobre una base existente |
| AC-NF-05 | Logs sin tokens ni contraseñas |

## 4. Matriz de trazabilidad (se completa en cada fase)

| CU | Reglas | Endpoint(s) | AC | Pruebas | Fase |
|---|---|---|---|---|:-:|
| CU-02 | RN-ACC-05, RN-SES-01 | `POST /public/tables/{code}/sessions` | AC-CU-02-* | CL-027, E2E-01 | 4 |
| CU-07 | RN-ORD-01…07,17 | `POST /sessions/current/orders` | AC-CU-07-* | CL-001/060/061/066, E2E-01 | 4 |
| CU-22 | RN-ORD-08,10 | `POST /staff/orders/{id}/accept` | AC-CU-22-* | CL-020/072/124, E2E-03 | 4 |
| CU-25 | RN-PRE-02,04,05 | `POST /staff/order-items/{id}/…` | AC-CU-25-* | CL-028/029/080, E2E-05 | 5 |
| CU-12 | RN-BIL-02…04 | `POST /sessions/current/payments` | AC-CU-12-* | CL-023/100/005, E2E-06 | 7 |
| CU-34 | RN-SES-05 | `POST /staff/sessions/{id}/close` | AC-CU-34-* | CL-109/110/111, E2E-06 | 7 |

---
# Criterios añadidos en v0.2

### Opciones (CU-05/07)
- **AC-CU-07-i** El servidor calcula `unitPriceMinor = base + Σ deltas` y guarda snapshot de opciones; cambiar el catálogo después no altera el pedido.
- **AC-CU-07-j** Las reglas de grupo (obligatorio, `SINGLE`, mín./máx.) se validan en el servidor (CL-170/174).
- **AC-CU-07-k** La cola de estación muestra las opciones de cada ítem.

### Propina (CU-12)
- **AC-CU-12-g** La propina nunca modifica el saldo; aparece separada en cuenta y comprobante.
- **AC-CU-12-h** Pago solo propina permitido con sesión abierta y cargos > 0.
- **AC-CU-12-i** Si el pago principal falla, la propina tampoco se registra (CL-183).

### Ajustes (CU-37/38)
- **AC-CU-37-a** Un ajuste se congela al aplicarse y no cambia con pedidos posteriores (CL-192).
- **AC-CU-37-b** El Mozo no puede superar su límite; el Dueño sí; todo queda auditado con motivo.
- **AC-CU-37-c** Un ajuste nunca deja el saldo negativo (CL-191/195).
- **AC-CU-37-d** El cliente no ve el motivo interno.

### Llamado al mozo (CU-62) · Idiomas (CU-47) · Aislamiento
- **AC-CU-62-a** Un solo llamado activo por sesión y motivo; el personal lo recibe en tiempo real.
- **AC-CU-47-a** Con `en`, menú e interfaz salen en inglés con fallback a `es-AR` donde falte traducción.
- **AC-G-09** Ningún dato cruza entre dos restaurantes (prueba con dos restaurantes sembrados).

---
# Criterios añadidos en el Paso 3 (2026-10-11)

### Sesión abierta por el personal (CU-30, CU-03, CU-13; D-143)
- **AC-CU-30-a** Una sesión abierta por el personal nace **sin `HOST`** y con `joinCode`.
- **AC-CU-03-d** En una sesión sin HOST, el primer participante que se une pasa a `HOST`; los siguientes son `MEMBER` (CL-055).
- **AC-CU-03-e** Dos uniones simultáneas a una sesión sin HOST dejan exactamente un `HOST` y un `MEMBER`, y ninguna recibe error (CL-056). La prueba debe correr contra PostgreSQL real, no contra un mock, porque lo que se verifica es el índice único parcial.
- **AC-CU-13-a** Mientras la sesión no tiene HOST, el personal con `participant.revoke` puede revocar participantes y rotar el código.

### Menú y restaurante (CU-05; D-144)
- **AC-CU-05-a** Sin token, `GET /menu` usa `restaurantId` o, si falta, el restaurante predeterminado; con un id desconocido responde `404` (CL-216/217).
- **AC-CU-05-b** Con token, manda el restaurante del token; un `restaurantId` distinto responde `404`.

### Pago mínimo (CU-12; D-158)
- **AC-CU-12-j** Con saldo menor a `minPaymentMinor`, se acepta un pago de **exactamente** el saldo; cualquier otro monto es rechazado (CL-118).
- **AC-CU-12-k** Con saldo mayor o igual al mínimo, un pago menor al mínimo responde `PAYMENT_AMOUNT_INVALID` (CL-102).

### Cierre forzado (CU-35; D-159)
- **AC-CU-35-b** Los pagos y propinas ya registrados se conservan tras el cierre forzado.
- **AC-CU-35-c** Si tras cancelar queda saldo < 0, se registra en la misma transacción un reembolso simulado automático por el exceso, sin devolver propina, auditado con el actor (CL-119).
- **AC-CU-35-d** Si queda saldo > 0, se anota como saldo no cobrado en la auditoría y en el comprobante (CL-112).

### Límite del Mozo (CU-37; D-142)
- **AC-CU-37-e** El límite del Mozo es **acumulado**: varios descuentos chicos que sumen más del 15 % de los cargos brutos son rechazados con `ADJUSTMENT_LIMIT_EXCEEDED` (CL-199). Los ajustes anulados no cuentan.
