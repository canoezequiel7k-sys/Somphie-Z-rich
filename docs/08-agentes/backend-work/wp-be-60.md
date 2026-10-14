# Ficha WP-BE-60 — Tiempo real

## WP-BE-60-T01 — Persistencia y stream SSE

- **Estado/prioridad/tipo:** Investigación completada; implementación bloqueada por WP-ARQ-60 · P3 · decisión previa + implementación.
- **Evidencia verificada (2026-10-14):** no hay módulo `notifications`, migración `event_log`, publisher ni `/events/stream` en backend. V7 `audit_event` registra acciones de auditoría transaccionales, pero no guarda el sobre/ID secuencial ni sirve como outbox. OpenAPI ya define alcance, heartbeat 25 s, `Last-Event-ID`, retención 48 h/410; [`ADR-0006`](../../00-gobierno/adr/ADR-0006-tiempo-real-sse.md) sigue “propuesta”, pese a D-026/D-118, y WP-ARQ-60 exige spike + aceptación/reemplazo antes del código.
- **Dependencia funcional observada:** Floor persiste llamados y expone el estado por REST; los eventos de dominio `waiter.called` y `waiter.updated` de RN-WCL deben integrarse en el outbox/SSE al completar este paquete, sin revertir la operación ante fallo de difusión.
- **Lectura obligatoria:** WP-ARQ-60/ADR-0006, OpenAPI `/events/stream`, `LastEventId`, eventos en [`tiempo-real.md`](../../03-arquitectura/tiempo-real.md), RN-RT-01…06, D-026/D-118, arquitectura backend y auditoría/transacciones.
- **Precondición/bloqueo:** WP-ARQ-60 debe realizar el spike y aceptar/reemplazar ADR-0006. No crear tabla parcial, publisher, endpoint o transporte antes de esa confirmación: cambian persistencia, recuperación, streaming y límites operativos como un conjunto.
- **Alcance tras aprobación:** `event_log`, `AFTER_COMMIT`, publisher, autorización scope session/staff, heartbeat, reanudación y error 410; sin payload sensible, el evento es pista para reconsulta.
- **Pruebas:** commit/rollback versus evento, aislamiento de scopes, `Last-Event-ID` límite/retención, heartbeat/reconexión, duplicados/orden; integración PostgreSQL y seguridad.
- **Aceptación:** contrato completo, eventos solo post-commit, 410 cuando corresponde, no fuga tenant/sesión; ArchUnit y suite. Documentar recursos/restricciones de conexiones.
- **Dependencias afectadas:** Q-27/WP-BE-52 necesita la secuencia de versión + `catalog.updated`; WP-BE-51 necesita los avisos `waiter.called`/`waiter.updated`. Ninguna se resuelve solo con `audit_event`. WP-BE-50 ya está completada localmente.
- **Próximo paso habilitante:** WP-ARQ-60 (spike y resolución explícita de ADR-0006); luego ejecutar WP-BE-60 completa en una sola línea de persistencia/publicación/SSE y validar PostgreSQL/seguridad. Clientes pueden usar mocks sin esperar esta ficha.
