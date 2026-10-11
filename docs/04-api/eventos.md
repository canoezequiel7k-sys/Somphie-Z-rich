# Catálogo de eventos en tiempo real

## 1. Sobre (envelope) — formato SSE
```
id: 128
event: order.updated
data: {"eventId":128,"type":"order.updated","aggregateType":"ORDER","aggregateId":"e91a…","version":4,"sessionId":"5d3e…","occurredAt":"2026-10-11T15:05:00Z"}
```
- `id` = `event_log.id` (creciente) → `Last-Event-ID`.
- `data` es una **pista mínima**: sin datos sensibles ni precios. El cliente reconsulta el recurso.
- `version` permite descartar eventos atrasados.

## 2. Eventos
| Evento | Cuándo | Aggregate | Quién lo recibe | Acción del cliente |
|---|---|---|---|---|
| `order.created` | Se crea un pedido | ORDER | Staff (con `order.read`); participantes de la sesión | Staff: refrescar cola entrantes. Cliente: refrescar pedidos |
| `order.updated` | Cambia estado/ítems (aceptado, rechazado, expirado, preparación, entrega, exclusión) | ORDER | Staff; participantes | Reconsultar `GET /orders/{id}` |
| `item.updated` | Cambia un ítem | ITEM (+orderId) | Staff de la estación; mozos; participantes | Reconsultar cola / pedido |
| `session.updated` | Participantes, joinCode, apertura | SESSION | Staff; participantes | Reconsultar sesión |
| `session.closed` | Sesión cerrada | SESSION | Staff; participantes | Mostrar "visita finalizada" |
| `bill.updated` | Cambia cuenta/pagos | SESSION | Staff (`session.read`); participantes | Reconsultar cuenta |
| `catalog.updated` | Cambia menú/disponibilidad | CATALOG | Todos los streams | Reconsultar `GET /menu` (ETag) |
| `table.updated` | Cambia el estado de mesa derivado | TABLE | Staff | Refrescar tablero |
| `waiter.called` (F9) | Cliente llama al mozo | SESSION | Staff `session.read` | Notificar |
| `heartbeat` (comentario `:`) | Cada 25 s | — | Todos | Mantener conexión |

## 3. Reglas
1. Se emiten **después del commit**; se registran en `event_log` en la misma transacción.
2. Filtrado por alcance en servidor (RN-RT-02).
3. Retención de `event_log`: 48 h; fuera de eso → `410 EVENT_ID_TOO_OLD`.
4. Los clientes ignoran tipos desconocidos (compatibilidad hacia adelante).

## 4. Añadidos en v0.2
| Evento | Cuándo | Quién lo recibe | Acción |
|---|---|---|---|
| `waiter.called` | Cliente llama al mozo | Staff con `waiter_call.handle` | Mostrar aviso y insignia en la mesa |
| `waiter.updated` | Se toma, resuelve o expira | Staff y participantes de la sesión | Reconsultar |
| `catalog.updated` | También al cambiar opciones o traducciones | Todos | Reconsultar `GET /menu` |
