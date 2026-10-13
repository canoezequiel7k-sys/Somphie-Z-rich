# Ficha WP-BE-60 — Tiempo real

## WP-BE-60-T01 — Persistencia y stream SSE

- **Estado/prioridad/tipo:** No iniciado, bloqueado por decisión ARQ-60 · P3 · decisión previa + implementación.
- **Evidencia:** no hay módulo `notifications`, `event_log` ni `/events/stream` en código. OpenAPI ya define alcance, heartbeat 25 s, `Last-Event-ID`, retención 48 h/410; [`ADR-0006`](../../00-gobierno/adr/ADR-0006-tiempo-real-sse.md) sigue “propuesta”.
- **Lectura obligatoria:** WP-ARQ-60/ADR-0006, OpenAPI `/events/stream`, `LastEventId`, eventos en [`tiempo-real.md`](../../03-arquitectura/tiempo-real.md), RN-RT-01…06, D-026/D-118, arquitectura backend y auditoría/transacciones.
- **Precondición:** WP-ARQ-60 debe aceptar/reemplazar ADR-0006 tras spike. No fijar arquitectura de transporte antes.
- **Alcance tras aprobación:** `event_log`, `AFTER_COMMIT`, publisher, autorización scope session/staff, heartbeat, reanudación y error 410; sin payload sensible, el evento es pista para reconsulta.
- **Pruebas:** commit/rollback versus evento, aislamiento de scopes, `Last-Event-ID` límite/retención, heartbeat/reconexión, duplicados/orden; integración PostgreSQL y seguridad.
- **Aceptación:** contrato completo, eventos solo post-commit, 410 cuando corresponde, no fuga tenant/sesión; ArchUnit y suite. Documentar recursos/restricciones de conexiones.
- **Dep.:** WP-BE-50 según roadmap. No paralelizar implementación con la decisión. Clientes pueden usar mocks sin esperar esta ficha.
