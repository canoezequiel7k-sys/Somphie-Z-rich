# Changelog del contrato de API

Formato: [versión] fecha — tipo (`added` / `changed` / `deprecated` / `removed` / `fixed`) — descripción — **impacto en Web / Android / Backend**.

## [0.1.0] — borrador inicial (Fase 0)
- added: endpoints, errores y eventos descritos en `endpoints.md`, `catalogo-errores.md`, `eventos.md`.
- Impacto: ninguno todavía (no hay implementación).
- Próximo paso: `openapi.yaml` v0.1 con esquemas y ejemplos (WP-ARQ-10).

## [0.2.0] — borrador (decisiones de la validación v0.1)
- added: opciones/modificadores en menú y pedido (`optionGroups`, `optionIds`, snapshot de opciones).
- added: `tipMinor` en pagos; `adjustmentsMinor`, `tipsMinor` y `adjustments[]` en la cuenta.
- added: endpoints de ajustes, llamados al mozo, opciones (disponibilidad y admin), traducciones, `GET /media/*`.
- added: `Accept-Language` en catálogo; `productNameI18n` en ítems.
- changed: `balance = charges − adjustments − paid`; `payment.amountMinor` admite 0 si hay propina.
- added: errores `OPTION_*`, `TIP_*`, `ADJUSTMENT_*`, `WAITER_CALL_*`, `TRANSLATION_INVALID`.
- Impacto: Web, Android y Backend (todavía sin implementación; el `openapi.yaml` v0.1 nacerá ya con esto).

## [0.2.1] — `openapi.yaml` completo (WP-ARQ-10)
- added: paths del personal (tablero, sesión manual, pedido en nombre de la mesa, pagos en efectivo, anulación, reembolso, cierre, cierre forzado, ajustes, disponibilidad, llamados al mozo), `GET /events/stream`, `GET /version`, `GET /media/{path}`.
- added: `components` completo (esquemas, parámetros, cabeceras, respuestas de error y ejemplos).
- added: `SignedMoneyMinor` para `balanceMinor` (puede ser negativo); el resto del dinero usa `MoneyMinor` (nunca negativo).
- changed: `GET /menu` devuelve `image: {path, version, alt}` (reemplaza `image.url`); `ETag` = `catalogVersion` + idioma, con `Vary: Accept-Language`.
- proposed (confirmar con Web y Android): `Participant.revoked`, `Order.rejection`, campos de `StaffTable`, cuerpo de `StaffOpenSessionResponse`.
- Impacto: Web y Android (generar tipos y mocks desde este archivo); Backend (contrato para tests con swagger-request-validator).
