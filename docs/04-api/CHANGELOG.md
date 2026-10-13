# Changelog del contrato de API

Formato: [versión] fecha — tipo (`added` / `changed` / `deprecated` / `removed` / `fixed`) — descripción — **impacto en Web / Android / Backend**.

Reglas (D-150):
- **Lo más reciente va arriba.** `info.version` de `openapi.yaml` debe coincidir con la primera entrada de este archivo.
- Versiones `0.x` = borrador. `1.0.0` = contrato congelado al cerrar la Fase 1.
- Las fechas anteriores al 2026-10-11 no se registraron en su momento (el historial de Git empieza ese día): se marcan "fecha no registrada" en vez de inventarlas.

## [0.2.6] — 2026-10-12 — completar snapshots y respuestas de preparación (WP-BE-50)
Cambio compatible: agrega los identificadores de opciones históricas y timestamps opcionales de transición.
- added: `OrderItemOption.groupId` y `optionId`, conservados en cada snapshot.
- added: timestamps opcionales `queuedAt`, `startedAt`, `readyAt`, `deliveredAt` y `cancelledAt`.
- changed: `deliver-ready` aclara que procesa los ítems `READY` al comenzar; sin ítems listos no modifica el pedido.
- changed: `info.version` = `0.2.6` y el ejemplo de `GET /version` (`contractVersion`).
- Impacto: **Web y Android** (pueden mostrar opciones y tiempos históricos); **Backend** (persistencia y respuestas de preparación).

## [0.2.5] — 2026-10-12 — alinear revocación del HOST con RN-ACC-10
Cambio **compatible**: aclara quién puede revocar al HOST y cuándo aplica el error existente.
- changed: el HOST no puede revocarse a sí mismo; el personal puede revocarlo, en consistencia con RN-ACC-10 y el dominio.
- changed: `info.version` = `0.2.5` y el ejemplo de `GET /version` (`contractVersion`).
- Impacto: **Web y Android** (aclara respuestas ya documentadas); **Backend** (el dominio ya aplica este comportamiento).

## [0.2.4] — 2026-10-12 — aclarar expiración de pedidos (H-26, H-27, H-28)
Cambio **compatible**: precisa cuándo se aplican errores existentes y el límite temporal, sin cambiar esquemas ni códigos.
- changed: la aceptación depende del estado actual; superar el TTL no bloquea si el job aún no marcó `EXPIRED`.
- changed: rechazar un pedido `EXPIRED` responde `ORDER_EXPIRED`; otros estados ya decididos responden `ORDER_ALREADY_DECIDED`.
- fixed: CU-50 expira al cumplirse el TTL (`createdAt + TTL <= now`) y el job no sobrescribe decisiones concurrentes.
- changed: `info.version` = `0.2.4` y el ejemplo de `GET /version` (`contractVersion`).
- Impacto: **Web y Android** (interpretación consistente de respuestas ya documentadas); **Backend** (job futuro y manejo de estados según el contrato).

## [0.2.3] — 2026-10-12 — cortesía solo sobre ítems aceptados (H-29)
Cambio **compatible**: solo aclara una descripción; no cambia esquemas ni códigos de error.
- fixed: `applyAdjustment` documenta que `ADJUSTMENT_ITEM_INVALID` (409) también aplica a un ítem aún sin aceptar (`PENDING`), además de cancelado o ya con cortesía (RN-ADJ-04, RN-BIL-01).
- changed: `info.version` = `0.2.3` y el ejemplo de `GET /version` (`contractVersion`).
- Impacto: **Web y Android** (no ofrecer cortesía sobre ítems de pedidos `PENDING_REVIEW`); **Backend** (ya lo cumple `CourtesyRules`).

## [0.2.2] — 2026-10-11 — contrato alineado con D-142…D-159 (Paso 4)
Todos los cambios son **compatibles**: no se renombra ni se quita nada, no hay campos obligatorios nuevos y el único campo nuevo (`PublicTable.restaurantId`) es opcional.
- added: `PublicTable.restaurantId` (opcional, UUID), para que la web pida `GET /menu` del local correcto tras escanear el QR (D-144). Los tres ejemplos de `GET /public/tables/{tableCode}` lo incluyen.
- fixed: la descripción de `GET /menu` y del parámetro `restaurantId` contradecía RN-TEN-03: con token manda el restaurante del token (si `restaurantId` difiere → `404`); sin token se usa `restaurantId` o el predeterminado del despliegue; un id desconocido → `404` (D-144). Aún no hay implementación, así que no rompe a nadie.
- changed: `Participant.revoked` y `Order.rejection` dejan de ser "propuestos" y quedan aceptados; también los campos de `StaffTable` y el cuerpo de `StaffOpenSessionResponse` (D-145). Se quitan las marcas "Propuesto… Confirmar con Web y Android" de las descripciones.
- fixed: `TIP_NOT_ALLOWED` se documenta en la respuesta **409** de `createPayment` y `registerCashPayment`, con ejemplo (`ProblemTipNotAllowed`); la respuesta genérica `Unprocessable` (422) deja de decir `TIP_*` y nombra solo `TIP_INVALID` y `TIP_EXCEEDS_LIMIT` (D-147).
- fixed: `getReceipt` declara `x-permission: bill.read` (antes no tenía).
- changed: `joinSession` documenta la regla del `HOST` (D-143, RN-ACC-14/15): una sesión abierta por el personal nace sin `HOST`, el primero en unirse lo es, y si dos se unen a la vez uno queda `HOST` y el otro `MEMBER` sin error. Se suma un segundo ejemplo de respuesta (`firstJoinerBecomesHost`) y una descripción en `ParticipantRole`.
- changed: pago con mínimo dinámico `min(minPaymentMinor, saldo)` (D-158) en `createPayment`, `registerCashPayment` y `CreatePaymentRequest`. `PAYMENT_AMOUNT_INVALID` devuelve `minMinor` **ya calculado con el saldo**; se agrega el ejemplo `ProblemPaymentAmountInvalid` (422).
- changed: `forceCloseSession` documenta sus efectos (D-159, RN-SES-06): conserva pagos y propinas; con `balance < 0` registra en la misma transacción un reembolso simulado automático por el exceso (sin devolver propina); con `balance > 0` queda como saldo no cobrado en auditoría y comprobante.
- fixed: el ejemplo `ADJUSTMENT_LIMIT_EXCEEDED` pasa de `limitPercent: 10` a `15` (D-142), con `limitMinor: 750000` para mantener la proporción del ejemplo anterior. La descripción de `applyAdjustment` aclara que el límite del Mozo es **acumulado** sobre los cargos brutos.
- fixed: `createOrderOnBehalf` pasa de "en nombre de la mesa" a "en nombre del cliente", como en D-113, CU-31 y el permiso `order.create_on_behalf` (H-08).
- changed: `info.version` = `0.2.2` y el ejemplo de `GET /version` (`contractVersion`).
- Impacto: **Web y Android** (regenerar tipos y mocks: `PublicTable.restaurantId` opcional; el mínimo de pago ya no es fijo); **Backend** (contrato para los tests con swagger-request-validator; las reglas ya están en `reglas-negocio.md`).

## [0.2.1] — fecha no registrada — `openapi.yaml` completo (WP-ARQ-10)
- added: paths del personal (tablero, sesión manual, pedido en nombre de la mesa, pagos en efectivo, anulación, reembolso, cierre, cierre forzado, ajustes, disponibilidad, llamados al mozo), `GET /events/stream`, `GET /version`, `GET /media/{path}`.
- added: `components` completo (esquemas, parámetros, cabeceras, respuestas de error y ejemplos).
- added: `SignedMoneyMinor` para `balanceMinor` (puede ser negativo); el resto del dinero usa `MoneyMinor` (nunca negativo).
- changed: `GET /menu` devuelve `image: {path, version, alt}` (reemplaza `image.url`); `ETag` = `catalogVersion` + idioma, con `Vary: Accept-Language`.
- proposed (confirmar con Web y Android): `Participant.revoked`, `Order.rejection`, campos de `StaffTable`, cuerpo de `StaffOpenSessionResponse`. **Aceptados en 0.2.2 (D-145).**
- Impacto: Web y Android (generar tipos y mocks desde este archivo); Backend (contrato para tests con swagger-request-validator).

## [0.2.0] — fecha no registrada — borrador (decisiones de la validación v0.1)
- added: opciones/modificadores en menú y pedido (`optionGroups`, `optionIds`, snapshot de opciones).
- added: `tipMinor` en pagos; `adjustmentsMinor`, `tipsMinor` y `adjustments[]` en la cuenta.
- added: endpoints de ajustes, llamados al mozo, opciones (disponibilidad y admin), traducciones, `GET /media/*`.
- added: `Accept-Language` en catálogo; `productNameI18n` en ítems.
- changed: `balance = charges − adjustments − paid`; `payment.amountMinor` admite 0 si hay propina.
- added: errores `OPTION_*`, `TIP_*`, `ADJUSTMENT_*`, `WAITER_CALL_*`, `TRANSLATION_INVALID`.
- Impacto: Web, Android y Backend (todavía sin implementación; el `openapi.yaml` v0.1 nacerá ya con esto).

## [0.1.0] — fecha no registrada — borrador inicial (Fase 0)
- added: endpoints, errores y eventos descritos en `endpoints.md`, `catalogo-errores.md`, `eventos.md`.
- Impacto: ninguno todavía (no hay implementación).
