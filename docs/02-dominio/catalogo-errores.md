# Catálogo de errores de negocio

Todos los errores viajan en formato **RFC 7807** (`application/problem+json`) con campos extra (ver `04-api/convenciones.md`):

```json
{
  "type": "https://api.somphiezurich.example/errors/order-already-decided",
  "title": "El pedido ya fue decidido",
  "status": 409,
  "code": "ORDER_ALREADY_DECIDED",
  "detail": "Mateo aceptó este pedido hace unos segundos.",
  "traceId": "9f1c2a7e…",
  "extra": { "decidedBy": "Mateo", "decidedAt": "2026-10-11T15:03:22Z", "currentStatus": "ACCEPTED" }
}
```

- `code` es **estable** (los clientes se programan contra él, no contra `title`/`detail`).
- `title`/`detail` en español, **sin trazas ni datos técnicos**.
- "Acción UI" indica el comportamiento esperado de Web/Android.

## 1. Genéricos

| Código | HTTP | Cuándo | Acción UI |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | Campos inválidos (`fieldErrors[]`) | Mostrar errores junto al campo |
| `MALFORMED_REQUEST` | 400 | JSON inválido | Error genérico + reportar (bug cliente) |
| `UNAUTHENTICATED` | 401 | Falta o es inválido el token | Volver a CU-01 (invitado) o login (personal) |
| `TOKEN_EXPIRED` | 401 | Access token vencido | Refresh automático una vez; si falla, login |
| `TOKEN_REVOKED` | 401 | Token de invitado revocado | Limpiar token y volver a CU-01 |
| `FORBIDDEN` | 403 | Falta permiso o alcance | Mensaje "No tenés permiso"; no reintentar |
| `RESOURCE_NOT_FOUND` | 404 | Recurso inexistente o de otro alcance | Volver atrás |
| `STATE_CONFLICT` | 409 | Conflicto de estado genérico | Refrescar y reintentar manual |
| `OPTIMISTIC_LOCK_FAILED` | 409 | Editado por otro (`version`) | Recargar y reaplicar |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Falta header obligatorio | Bug de cliente |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Misma clave, cuerpo distinto | Bug de cliente: generar clave nueva |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | Misma clave en proceso | Esperar 1–2 s y reintentar con la misma clave |
| `RATE_LIMITED` | 429 | Demasiadas solicitudes (`Retry-After`) | Mostrar espera |
| `PAYLOAD_TOO_LARGE` | 413 | Cuerpo/imagen demasiado grande | Mensaje |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Content-Type no soportado | Bug de cliente |
| `INTERNAL_ERROR` | 500 | Error no previsto | "Algo salió mal" + `traceId` + reintentar |
| `SERVICE_UNAVAILABLE` | 503 | Base/servicio caído o arrancando | Reintentar con backoff |

## 2. Identidad

| Código | HTTP | Cuándo | Acción UI |
|---|---|---|---|
| `INVALID_CREDENTIALS` | 401 | Email o contraseña incorrectos (mensaje genérico) | Mensaje genérico |
| `ACCOUNT_DISABLED` | 403 | Usuario deshabilitado | "Contactá al dueño" |
| `ACCOUNT_TEMPORARILY_LOCKED` | 429 | Demasiados intentos (`Retry-After`) | Mostrar espera |
| `EMAIL_NOT_VERIFIED` | 403 | Falta verificar (extras) | Ofrecer reenviar |
| `PASSWORD_TOO_WEAK` | 422 | No cumple política | Mostrar requisitos |
| `REFRESH_TOKEN_REUSED` | 401 | Refresh reutilizado | Forzar login |
| `PASSWORD_CHANGE_REQUIRED` | 403 | Contraseña temporal | Ir a cambio de contraseña |
| `LAST_OWNER_PROTECTED` | 409 | Quitar al último dueño | Mensaje |
| `USER_NOT_FOUND` | 404 | Usuario inexistente | — |
| `ROLE_NOT_FOUND` | 404 | Rol inexistente | — |

## 3. Mesas y sesiones

| Código | HTTP | Cuándo | Acción UI | Extra |
|---|---|---|---|---|
| `TABLE_CODE_INVALID` | 404 | QR inexistente/regenerado | "Este QR ya no es válido" | — |
| `TABLE_UNAVAILABLE` | 409 | Mesa inactiva | "Mesa no disponible" | — |
| `RESTAURANT_CLOSED` | 409 | Fuera de horario | Menú solo lectura | `opensAt` |
| `JOIN_CODE_REQUIRED` | 403 | Sesión abierta sin código | Pedir código | — |
| `JOIN_CODE_INVALID` | 403 | Código incorrecto/rotado | "Código incorrecto" | — |
| `JOIN_CODE_RATE_LIMITED` | 429 | Intentos agotados | Mostrar espera | `retryAfterSec` |
| `SESSION_FULL` | 409 | Máx. participantes | Pedir al personal | — |
| `SESSION_NOT_FOUND` | 404 | No existe/otra | CU-01 | — |
| `SESSION_CLOSED` | 409 | Operación en sesión cerrada | SC-C-13 | — |
| `SESSION_ACCESS_REVOKED` | 403 | Participante revocado | Pantalla de acceso retirado | — |
| `SESSION_ALREADY_OPEN` | 409 | Carrera al abrir | Pasar a unirse | — |
| `SESSION_HAS_PENDING_ORDERS` | 409 | Cierre bloqueado | Lista de pedidos | `orderIds[]` |
| `SESSION_HAS_ACTIVE_ITEMS` | 409 | Cierre bloqueado | Lista de ítems | `itemIds[]` |
| `SESSION_BALANCE_PENDING` | 409 | Cierre con saldo ≠ 0 | Mostrar saldo | `balanceMinor` |
| `PARTICIPANT_NOT_FOUND` | 404 | — | — | — |
| `HOST_CANNOT_BE_REVOKED` | 409 | — | Mensaje | — |
| `TABLE_HAS_OPEN_SESSION` | 409 | Desactivar mesa ocupada | Mensaje | `sessionId` |
| `TABLE_LABEL_DUPLICATE` | 409 | Etiqueta repetida | Campo | — |

## 4. Pedidos e ítems

| Código | HTTP | Cuándo | Acción UI | Extra |
|---|---|---|---|---|
| `ORDER_EMPTY` | 422 | Sin ítems | Mensaje | — |
| `ORDER_TOO_MANY_LINES` | 422 | > máx. líneas | Mensaje | `max` |
| `ORDER_QUANTITY_EXCEEDED` | 422 | Cantidad > máx. | Ajustar | `max` |
| `ORDER_PENDING_LIMIT_REACHED` | 409 | Demasiados pendientes | "Esperá a que el personal responda" | `max` |
| `PRODUCT_NOT_FOUND` | 422 | Producto inexistente | Refrescar menú | `productIds[]` |
| `PRODUCT_UNAVAILABLE` | 409 | Agotado/archivado | Marcar en carrito | `productIds[]` |
| `PRICE_CHANGED` | 409 | Total reconocido ≠ calculado | Mostrar nuevos precios y reconfirmar | `currentTotalMinor`, `items[]` |
| `ORDER_NOT_FOUND` | 404 | — | — | — |
| `ORDER_ALREADY_DECIDED` | 409 | Aceptado/rechazado antes | Mostrar quién/qué | `decidedBy`, `decidedAt`, `currentStatus` |
| `ORDER_NOT_CANCELLABLE` | 409 | No está `PENDING_REVIEW` | Refrescar | `currentStatus` |
| `ORDER_EXPIRED` | 409 | Expiró | Refrescar | — |
| `ORDER_ALL_ITEMS_EXCLUDED` | 422 | Excluyó todo | "Usá Rechazar" | — |
| `ITEM_NOT_FOUND` | 404 | — | — | — |
| `ITEM_INVALID_TRANSITION` | 409 | Transición no permitida | Refrescar | `currentStatus` |
| `ITEM_STATION_FORBIDDEN` | 403 | Estación no asignada | Mensaje | — |
| `ITEM_NOT_CANCELLABLE` | 409 | Ya entregado | Mensaje | — |
| `ITEM_UNDO_WINDOW_EXPIRED` | 409 | Ventana vencida | Mensaje | — |

## 5. Pagos

| Código | HTTP | Cuándo | Acción UI | Extra |
|---|---|---|---|---|
| `PAYMENT_AMOUNT_INVALID` | 422 | `amount` < `min(minPaymentMinor, balance)` (D-158) o no entero | Corregir | `minMinor` (ya calculado con el saldo) |
| `PAYMENT_EXCEEDS_BALANCE` | 409 | > saldo | Ajustar al saldo | `balanceMinor` |
| `PAYMENT_NOTHING_TO_PAY` | 409 | Saldo ≤ 0 con monto principal > 0 (un pago solo propina no lo produce, D-146) | Refrescar cuenta | — |
| `PAYMENT_DECLINED` | 422 | Rechazo simulado (demo) | "Pago rechazado" | `paymentId` |
| `PAYMENT_NOT_FOUND` | 404 | — | — | — |
| `PAYMENT_NOT_VOIDABLE` | 409 | No `SUCCEEDED` | Mensaje | — |
| `REFUND_EXCEEDS_PAID` | 409 | > pagado neto | Corregir | `maxRefundMinor` |
| `RECEIPT_NOT_AVAILABLE` | 409 | Sesión abierta | "Disponible al cerrar" | — |

## 6. Catálogo y administración

| Código | HTTP | Cuándo |
|---|---|---|
| `CATEGORY_NOT_FOUND` | 404 | — |
| `CATEGORY_NOT_EMPTY` | 409 | Archivar categoría con productos activos |
| `PRODUCT_NAME_DUPLICATE` | 409 | Nombre repetido en la categoría |
| `IMAGE_INVALID` | 422 | Formato/tamaño/alt faltante |
| `SETTINGS_INVALID` | 422 | Valores fuera de rango |

## 7. Tiempo real

| Código | HTTP | Cuándo | Acción UI |
|---|---|---|---|
| `STREAM_SCOPE_FORBIDDEN` | 403 | Pide eventos fuera de su alcance | No reintentar |
| `EVENT_ID_TOO_OLD` | 410 | `Last-Event-ID` fuera de retención | Refresco completo |

## 7.1 Añadidos en v0.2

| Código | HTTP | Cuándo | Acción UI | Extra |
|---|---|---|---|---|
| `OPTION_NOT_FOUND` | 422 | Opción inexistente o no pertenece al producto | Refrescar menú | `optionIds[]` |
| `OPTION_UNAVAILABLE` | 409 | Opción agotada/archivada | Marcar en el carrito | `optionIds[]` |
| `OPTION_SELECTION_INVALID` | 422 | No se cumple el grupo (obligatorio, mín./máx., `SINGLE`) | Resaltar el grupo | `groupId`, `min`, `max`, `selected` |
| `OPTION_GROUP_NOT_FOUND` | 404 | Admin: grupo inexistente | — | — |
| `TRANSLATION_INVALID` | 422 | Admin: traducción mal formada | Campo | `locale` |
| `TIP_INVALID` | 422 | Propina negativa/no entera, o pago vacío (monto 0 y propina 0) | Corregir | — |
| `TIP_EXCEEDS_LIMIT` | 422 | Propina sobre el tope de cordura | "¿Seguro?" con tope | `maxTipMinor` |
| `TIP_NOT_ALLOWED` | 409 | Sin cargos en la sesión | Mensaje | — |
| `ADJUSTMENT_INVALID` | 422 | Porcentaje fuera de 1–100, monto ≤ 0, motivo faltante | Campo | — |
| `ADJUSTMENT_EXCEEDS_BALANCE` | 409 | El ajuste deja saldo negativo | Ajustar al saldo | `maxAdjustmentMinor` |
| `ADJUSTMENT_LIMIT_EXCEEDED` | 403 | Supera el límite del rol: es **acumulado** (lo ya aplicado por ese Mozo + el ajuste nuevo supera el 15 % de los cargos brutos, D-142) | "Requiere al Dueño" | `limitPercent`, `limitMinor` |
| `ADJUSTMENT_ITEM_INVALID` | 409 | Ítem cancelado o ya con cortesía | Refrescar | `itemId` |
| `ADJUSTMENT_NOT_FOUND` | 404 | — | — | — |
| `ADJUSTMENT_NOT_VOIDABLE` | 409 | Ya anulado, sesión cerrada o fuera de ventana | Mensaje | — |
| `WAITER_CALL_ALREADY_ACTIVE` | 409 | Ya hay un llamado activo del mismo motivo | "Ya avisamos al personal" | — |
| `WAITER_CALL_COOLDOWN` | 429 | Espera de 60 s | Cuenta regresiva | `retryAfterSec` |
| `WAITER_CALL_NOT_FOUND` | 404 | — | — | — |

## 8. Reglas para agregar errores
1. Se agrega **primero aquí**, con HTTP, cuándo, acción UI y `extra`.
2. Se refleja en `openapi.yaml` (componente `Problem` + ejemplos).
3. Cada error tiene **al menos un test** que lo produce y verifica que no cambió datos.
4. Los códigos no se renombran: se deprecan y se agrega uno nuevo.
