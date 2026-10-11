# Endpoints (borrador v0.1)

Auth: `public` · `guest` (token de participante) · `staff` (JWT + permiso) · `owner` (JWT + permiso admin). **Idem** = `Idempotency-Key` obligatoria. Fase = primera fase donde debe existir en el backend.

## 1. Catálogo y menú
| Método | Ruta | Auth/Permiso | Idem | CU | Fase | Descripción |
|---|---|---|:-:|---|:-:|---|
| GET | `/menu` | public | — | CU-05 | 3 | Categorías con productos; `ETag` = `catalogVersion` |
| GET | `/products/{id}` | public | — | CU-05 | 3 | Detalle de producto |

## 2. Acceso por QR y sesión (participante)
| Método | Ruta | Auth/Permiso | Idem | CU | Fase | Descripción |
|---|---|---|:-:|---|:-:|---|
| GET | `/public/tables/{tableCode}` | public | — | CU-01 | 4 | Estado de la mesa |
| POST | `/public/tables/{tableCode}/sessions` | public | ✔ | CU-02 | 4 | Abrir sesión |
| POST | `/public/tables/{tableCode}/join` | public | ✔ | CU-03 | 4 | Unirse con código |
| GET | `/sessions/current` | guest | — | CU-04 | 4 | Sesión actual, participantes, `joinCode` |
| GET | `/sessions/current/participants` | guest | — | CU-13 | 4 | Lista |
| DELETE | `/sessions/current/participants/{id}` | guest (host) | — | CU-13 | 4 | Revocar |
| POST | `/sessions/current/join-code/rotate` | guest (host) | — | CU-13 | 4 | Nuevo código |

## 3. Pedidos (participante)
| Método | Ruta | Auth/Permiso | Idem | CU | Fase | Descripción |
|---|---|---|:-:|---|:-:|---|
| POST | `/sessions/current/orders` | guest | ✔ | CU-07 | 4 | Enviar pedido |
| GET | `/sessions/current/orders` | guest | — | CU-08 | 4 | Pedidos de la sesión (todos) |
| GET | `/orders/{id}` | guest | — | CU-08 | 4 | Detalle (solo de su sesión) |
| POST | `/orders/{id}/cancel` | guest | ✔ | CU-09 | 4 | Cancelar pendiente |

## 4. Cuenta y pagos
| Método | Ruta | Auth/Permiso | Idem | CU | Fase | Descripción |
|---|---|---|:-:|---|:-:|---|
| GET | `/sessions/current/bill` | guest | — | CU-11 | 7 | Cuenta derivada |
| POST | `/sessions/current/payments` | guest | ✔ | CU-12 | 7 | Pago simulado |
| GET | `/sessions/current/payments` | guest | — | CU-12 | 7 | Historial |
| GET | `/sessions/current/receipt.pdf` | guest | — | CU-14 | 7 | Comprobante (sesión `CLOSED`) |

## 5. Identidad
| Método | Ruta | Auth/Permiso | Idem | CU | Fase | Descripción |
|---|---|---|:-:|---|:-:|---|
| POST | `/auth/login` | public | — | CU-20 | 4 | Login |
| POST | `/auth/refresh` | public (refresh) | — | CU-20 | 4 | Rotar tokens |
| POST | `/auth/logout` | staff | — | CU-20 | 4 | Revoca refresh |
| POST | `/auth/change-password` | staff | — | RN-IDN-09 | 4 | Cambio (obligatorio con temporal) |
| GET | `/me` | staff | — | CU-20 | 4 | Usuario, permisos, estaciones |
| POST | `/auth/register` | public | — | CU-60 | 9 | Registro cliente |
| POST | `/auth/verify-email` | public | — | CU-60 | 9 | Verificar |
| POST | `/auth/password/forgot` · `/reset` | public | — | CU-60 | 9 | Recuperar |

## 6. Operación del personal
| Método | Ruta | Permiso | Idem | CU | Fase | Descripción |
|---|---|---|:-:|---|:-:|---|
| GET | `/staff/orders` | `order.read` | — | CU-21 | 4 | Filtros `status`, `sessionId`, cursor |
| GET | `/staff/orders/{id}` | `order.read` | — | CU-21 | 4 | Detalle (con `productCurrentlyAvailable`) |
| POST | `/staff/orders/{id}/accept` | `order.accept` | ✔ | CU-22 | 4 | Con `excludedItems` opcional |
| POST | `/staff/orders/{id}/reject` | `order.reject` | ✔ | CU-23 | 4 | `reasonCode`, `reasonText?` |
| GET | `/staff/stations` | `item.prepare` | — | CU-24 | 5 | Estaciones asignadas |
| GET | `/staff/stations/{stationId}/queue` | `item.prepare` + estación | — | CU-24 | 5 | Cola FIFO |
| POST | `/staff/order-items/{id}/start` | `item.prepare` | ✔ | CU-25 | 5 | `QUEUED→IN_PREPARATION` |
| POST | `/staff/order-items/{id}/ready` | `item.prepare` | ✔ | CU-25 | 5 | → `READY` |
| POST | `/staff/order-items/{id}/deliver` | `item.deliver` | ✔ | CU-26 | 5 | → `DELIVERED` |
| POST | `/staff/orders/{id}/deliver-ready` | `item.deliver` | ✔ | CU-26 | 5 | Entrega todos los `READY` |
| POST | `/staff/order-items/{id}/cancel` | `order.cancel` | ✔ | CU-27 | 5 | `reason` obligatorio |
| POST | `/staff/order-items/{id}/undo` | `item.undo` | ✔ | CU-28 | 5 | Revierte un paso |
| GET | `/staff/tables` | `table.read` | — | CU-29 | 5 | Tablero con `displayStatus` |
| GET | `/staff/sessions/{id}` | `session.read` | — | CU-29 | 5 | Detalle: pedidos, cuenta, participantes |
| POST | `/staff/tables/{tableId}/sessions` | `session.open_manual` | ✔ | CU-30 | 5 | Abrir sesión |
| POST | `/staff/sessions/{id}/orders` | `order.create_on_behalf` | ✔ | CU-31 | 7 | Pedido auto-aceptado |
| POST | `/staff/sessions/{id}/payments` | `payment.register_cash` | ✔ | CU-32 | 7 | Efectivo |
| POST | `/staff/payments/{id}/void` | `payment.void` | ✔ | CU-33 | 7 | Anular |
| POST | `/staff/sessions/{id}/refunds` | `payment.refund` | ✔ | CU-33 | 7 | Reembolso |
| POST | `/staff/sessions/{id}/close` | `session.close` | — | CU-34 | 7 | Cierre normal |
| POST | `/staff/sessions/{id}/force-close` | `session.force_close` | ✔ | CU-35 | 7 | `reason` |
| POST | `/staff/sessions/{id}/participants/{pid}/revoke` | `participant.revoke` | — | CU-13 | 5 | Revocar |
| POST | `/staff/sessions/{id}/join-code/rotate` | `participant.revoke` | — | CU-13 | 5 | Rotar código |
| PATCH | `/staff/products/{id}/availability` | `availability.toggle` | — | CU-36 | 5 | `{available}` |

## 7. Administración
| Método | Ruta | Permiso | CU | Fase |
|---|---|---|---|:-:|
| GET/POST/PATCH | `/admin/categories`, `/admin/categories/{id}` | `catalog.manage` | CU-40 | 8 |
| GET/POST/PATCH | `/admin/products`, `/admin/products/{id}` | `catalog.manage` | CU-40 | 8 |
| POST | `/admin/products/{id}/image` | `catalog.manage` | CU-40 | 8 |
| GET/POST/PATCH | `/admin/tables`, `/admin/tables/{id}` | `table.manage` | CU-41 | 8 |
| POST | `/admin/tables/{id}/regenerate-qr` | `table.manage` | CU-41 | 8 |
| GET/POST/PATCH | `/admin/users`, `/admin/users/{id}` | `user.manage` | CU-42 | 8 |
| POST | `/admin/users/{id}/disable` · `/enable` · `/reset-password` | `user.manage` | CU-42 | 8 |
| GET | `/admin/roles` | `user.manage` | CU-42 | 8 |
| GET | `/admin/reports/sales` · `/top-products` · `/orders-by-hour` · `/prep-times` · `/rejections` | `report.read` | CU-43 | 8 |
| GET | `/admin/reports/export.pdf` | `report.export` | CU-43 | 8 |
| GET | `/admin/audit-events` | `audit.read` | CU-44 | 8 |
| GET/PATCH | `/admin/settings` | `settings.manage` | CU-45 | 8 |
| GET | `/admin/sessions` · `/admin/orders` (consulta) | `session.read` / `order.read` | CU-29 | 8 |

## 8. Tiempo real y salud
| Método | Ruta | Auth | Fase | Descripción |
|---|---|---|:-:|---|
| GET | `/events/stream?scope=session\|staff` | guest / staff | 6 | SSE (`Last-Event-ID`) |
| GET | `/actuator/health` | public (reducido) | 2 | Salud |
| GET | `/version` | public | 2 | Versión de build y de contrato |

---

# Ejemplos de contrato (flujo núcleo)

## E1. `GET /menu`
```json
{
  "catalogVersion": 42,
  "currency": "ARS",
  "restaurantOpen": true,
  "categories": [
    {
      "id": "c1f0…", "name": "Café de especialidad", "sortOrder": 1,
      "products": [
        {
          "id": "9a2b…", "name": "Flat white", "description": "Doble espresso con leche texturizada.",
          "priceMinor": 485000, "available": true,
          "station": "BAR", "allergens": ["MILK"],
          "image": { "url": "https://…/flat-white.webp", "alt": "Flat white en taza de cerámica" }
        }
      ]
    }
  ]
}
```

## E2. `POST /public/tables/{tableCode}/sessions`
Request: `{ "displayName": "Clara" }` + `Idempotency-Key`
Response `201`:
```json
{
  "session": { "id": "5d3e…", "status": "OPEN", "tableLabel": "Mesa 7", "openedAt": "2026-10-11T15:00:10Z" },
  "participant": { "id": "ab12…", "displayName": "Clara", "role": "HOST" },
  "guestToken": "gt_x8Jm…(opaco)",
  "joinCode": "K7M2QX"
}
```

## E3. `POST /sessions/current/orders`
Request (+ `Idempotency-Key`):
```json
{
  "items": [
    { "productId": "9a2b…", "quantity": 2, "note": "Sin azúcar" },
    { "productId": "77cd…", "quantity": 1 }
  ],
  "note": "Gracias",
  "acknowledgedTotalMinor": 1450000
}
```
Response `201`:
```json
{
  "id": "e91a…", "displayCode": "#042", "status": "PENDING_REVIEW", "version": 1,
  "createdBy": { "participantId": "ab12…", "displayName": "Clara" },
  "createdAt": "2026-10-11T15:03:22Z",
  "items": [
    { "id": "i1…", "productName": "Flat white", "unitPriceMinor": 485000, "quantity": 2,
      "lineTotalMinor": 970000, "station": "BAR", "status": "PENDING", "note": "Sin azúcar" }
  ],
  "totalMinor": 1450000
}
```
Error `409 PRICE_CHANGED`:
```json
{ "code": "PRICE_CHANGED", "status": 409, "title": "Cambiaron algunos precios",
  "extra": { "currentTotalMinor": 1520000,
             "items": [ { "productId": "9a2b…", "unitPriceMinor": 520000, "available": true } ] } }
```

## E4. `POST /staff/orders/{id}/accept`
Request: `{ "excludedItems": [ { "itemId": "i2…", "reason": "OUT_OF_STOCK" } ] }`
Response `200`: el pedido completo con `status: "ACCEPTED"`, ítems `QUEUED` y el excluido `CANCELLED`, más `decidedBy` y `decidedAt`.

## E5. `GET /staff/stations/{id}/queue`
```json
{ "items": [
  { "itemId": "i1…", "orderId": "e91a…", "orderCode": "#042", "tableLabel": "Mesa 7",
    "productName": "Flat white", "quantity": 2, "note": "Sin azúcar",
    "status": "QUEUED", "queuedAt": "2026-10-11T15:04:01Z", "delayed": false }
], "nextCursor": null }
```

## E6. `GET /sessions/current/bill`
```json
{
  "currency": "ARS", "chargesMinor": 1450000, "paidMinor": 500000, "balanceMinor": 950000,
  "lines": [ { "orderCode": "#042", "productName": "Flat white", "quantity": 2, "unitPriceMinor": 485000, "lineTotalMinor": 970000 } ],
  "payments": [ { "id": "p1…", "amountMinor": 500000, "method": "SIMULATED_ONLINE", "status": "SUCCEEDED", "payerName": "Clara", "createdAt": "…" } ],
  "pendingOrdersNotIncluded": [ { "orderCode": "#043", "totalMinor": 300000 } ]
}
```

## E7. `GET /me` (staff)
```json
{ "id": "u1…", "email": "mateo@somphie.example", "displayName": "Mateo",
  "roles": ["WAITER"], "permissions": ["order.read","order.accept","order.reject","item.deliver","session.read","session.close","table.read","availability.toggle"],
  "stations": [], "mustChangePassword": false }
```

---
# Cambios de la v0.2

## A. Idioma
Todos los `GET` de catálogo aceptan `Accept-Language` (o `?lang=`). `GET /menu` devuelve textos ya localizados y `locale`. Los errores se localizan **en el cliente por `code`**.

## B. Menú con opciones (`GET /menu`, producto)
```json
{
  "id": "9a2b…", "name": "Flat white", "priceMinor": 485000, "available": true, "station": "BAR",
  "optionGroups": [
    { "id": "g1…", "name": "Tamaño", "selectionType": "SINGLE", "required": true, "minSelections": 1, "maxSelections": 1,
      "options": [
        { "id": "o1…", "name": "Chico", "priceDeltaMinor": 0, "available": true, "isDefault": true },
        { "id": "o2…", "name": "Grande", "priceDeltaMinor": 80000, "available": true, "isDefault": false } ] },
    { "id": "g2…", "name": "Leche", "selectionType": "SINGLE", "required": false, "minSelections": 0, "maxSelections": 1,
      "options": [ { "id": "o3…", "name": "Avena", "priceDeltaMinor": 60000, "available": true, "isDefault": false } ] }
  ]
}
```
## C. Pedido con opciones (`POST /sessions/current/orders`)
```json
{ "items": [ { "productId": "9a2b…", "quantity": 1, "optionIds": ["o2…","o3…"], "note": "Bien caliente" } ],
  "acknowledgedTotalMinor": 625000 }
```
El ítem de respuesta incluye `basePriceMinor`, `optionsDeltaMinor`, `unitPriceMinor` y `options: [{groupName, optionName, priceDeltaMinor}]`.

## D. Pago con propina (`POST /sessions/current/payments`)
```json
{ "amountMinor": 500000, "tipMinor": 50000, "method": "SIMULATED_ONLINE" }
```
Pago solo propina: `{ "amountMinor": 0, "tipMinor": 50000, ... }`. Respuesta: `{ payment, bill }`.

## E. Cuenta (`GET /sessions/current/bill`, campos nuevos)
```json
{ "chargesMinor": 1450000, "adjustmentsMinor": 145000, "paidMinor": 500000, "balanceMinor": 805000, "tipsMinor": 50000,
  "adjustments": [ { "id": "a1…", "kind": "DISCOUNT_PERCENT", "label": "Descuento", "amountMinor": 145000 } ] }
```
(`balanceMinor = chargesMinor − adjustmentsMinor − paidMinor`; la propina no entra.)

## F. Endpoints nuevos
| Método | Ruta | Auth/Permiso | Idem | CU | Fase |
|---|---|---|:-:|---|:-:|
| POST | `/staff/sessions/{id}/adjustments` | `adjustment.apply` / `apply_any` | ✔ | CU-37 | 7 |
| GET | `/staff/sessions/{id}/adjustments` | `session.read` | — | CU-37 | 7 |
| POST | `/staff/adjustments/{id}/void` | `adjustment.void` | ✔ | CU-38 | 7 |
| PATCH | `/staff/options/{id}/availability` | `availability.toggle` | — | CU-36 | 5 |
| POST | `/sessions/current/waiter-calls` | guest | ✔ | CU-62 | 9* |
| GET | `/sessions/current/waiter-calls` | guest | — | CU-62 | 9* |
| GET | `/staff/waiter-calls?status=OPEN,ACKNOWLEDGED` | `waiter_call.handle` | — | CU-62 | 9* |
| POST | `/staff/waiter-calls/{id}/acknowledge` · `/resolve` | `waiter_call.handle` | — | CU-62 | 9* |
| GET/POST/PATCH | `/admin/option-groups`, `/admin/option-groups/{id}` | `catalog.manage` | — | CU-46 | 8 |
| GET/POST/PATCH | `/admin/option-groups/{id}/options`, `/admin/options/{id}` | `catalog.manage` | — | CU-46 | 8 |
| PUT | `/admin/products/{id}/option-groups` | `catalog.manage` | — | CU-46 | 8 |
| PUT | `/admin/{categories\|products\|option-groups\|options}/{id}/translations/{locale}` | `catalog.manage` | — | CU-46 | 8 |
| GET | `/media/{path}` | public (solo dev) | — | RN-IMG | 3 |

\* Puede adelantarse a después de la Fase 6 (D-138).

Cuerpo de ajuste: `{ "kind": "DISCOUNT_PERCENT", "percentBp": 1000, "reasonCode": "SERVICE_ISSUE", "reasonText": "…" }` (`percentBp`: 1000 = 10 %). Cortesía: `{ "kind": "COURTESY_ITEM", "orderItemId": "…", "reasonCode": "COURTESY_HOUSE" }`. Motivos: `COURTESY_HOUSE`, `SERVICE_ISSUE`, `PROMOTION`, `STAFF`, `ERROR_CORRECTION`, `OTHER`.

## G. Cola de estación (campos nuevos en cada ítem)
`optionsSummary: "Grande · Avena"` y `options[]`, para que barra y cocina vean las opciones.

## H. Eventos nuevos
`waiter.called`, `waiter.updated` (ver `eventos.md`), `catalog.updated` también por opciones.
