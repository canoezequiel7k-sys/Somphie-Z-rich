# Fichas de cierre WP-BE-50

Alcance madre: [`roadmap-fases.md`, Fase 5](../roadmap-fases.md). Contrato vigente 0.3.0; reglas/transiciones y Q-24 son autoridad. La cola ordena/cursoriza por `accepted_at`, conserva `queued_at` histórico nullable y no reconstruye horas.

Común a las fichas: empezar con `AGENTS.md`, [`advances.md`](../../00-gobierno/advances.md), [`decisiones.md` Q-24](../../00-gobierno/decisiones.md), [`openapi.yaml`](../../04-api/openapi.yaml) y tests nombrados. No implementar contabilidad de cortesías: pertenece a BE-73 e integra con cargo de pedidos.

## WP-BE-50-T01 — Aprobación contractual Q-24

- **Estado/prioridad/tipo:** Cerrada (2026-10-13) · P0 · decisión/contrato.
- **Objetivo/evidencia:** decidir exposición y orden de ítems activos históricos sin `queued_at` o `accepted_at`.
- **Leer:** [`Q-24`](../../00-gobierno/decisiones.md), OpenAPI `GET /staff/stations/{stationId}/queue` y `StationQueueItem`/`StationQueue`; `V10__preparation_order_snapshots.sql`; `JdbcStationQueueRepository.kt`, `StationQueue.kt`, `GetStationQueueTest.kt`; reglas §4 de [`estados-y-transiciones.md`](../../02-dominio/estados-y-transiciones.md).
- **Dirección aprobada:** incluir los históricos activos con `queued_at = NULL`, sin inventar timestamps ni sustituirlos por `accepted_at`.
- **Decisión final:** FIFO y SLA por `acceptedAt`; exponer `queuedAt` nullable sin sustituirlo. `delayed` es boolean nullable y vale null únicamente si falta `acceptedAt`. Orden SQL/cursor `acceptedAt ASC NULLS LAST, itemId ASC`: el ID desempata claves coincidentes y estabiliza el grupo sin fecha, pero ese grupo no tiene FIFO inferible. Clientes deben admitir ambos nulls. No se reconstruyen fechas.
- **Acordado para MVP:** paginación keyset con consistencia eventual, sin snapshot entre requests; altas o cambios de estado pueden cambiar páginas posteriores, según análisis de [Q-24](../../00-gobierno/decisiones.md). Agregados de métricas pospuestos hasta definir objetivo/fórmula/período/población; conservar timestamps disponibles sin reconstruir históricos.
- **Contrato/compatibilidad:** OpenAPI 0.3.0 declara los dos campos presentes y nullable; cambio incompatible para consumidores que los tipaban como no nulos. CHANGELOG explica el impacto. Cursor eventual no promete recorrido íntegro frente a mutaciones.
- **Compatibilidad de cursor:** los tokens opacos previos al cambio de orden se rechazan como `VALIDATION_FAILED`; el consumidor reinicia desde la primera página.

## WP-BE-50-T02 — Alinear lista de pedidos de sesión

- **Estado/prioridad/tipo:** Completada (2026-10-13) · P1 · implementación + pruebas contractuales.
- **Objetivo/evidencia:** `OrdersController.listSessionOrders` hoy devuelve `List<Map<...>>` con seis campos; OpenAPI exige `OrderPage` (`items`, `nextCursor`) de `Order` completos. Faltan versionado/ítems y cursor contractual.
- **Leer:** método mencionado; `OrderPage`/`Order` en OpenAPI y criterios AC-CU-07/08; `Order`, `OrderItem`, pruebas de Orders existentes (`IdentityIntegrationTest`/`BackendSmokeTest` solo como referencias, confirmar cobertura).
- **Alcance:** DTO/mapeo, consulta eficiente/paginación/cursor, autorización por sesión, tests API+contrato e integración Postgres donde haga falta. Primero cotejar todos los campos obligatorios opcionales y cómo serializa historial.
- **Fuera:** no cambiar OpenAPI para aceptar el mapa simplificado sin aprobación ARQ; no modificar creación ni cancelación de pedido.
- **Aceptación:** respuesta de éxito pasa esquema contractual; orden más reciente primero; cursor estable/limit; nunca filtra otras sesiones; pruebas de casos vacío, página siguiente y recurso no propio. Actualizar CHANGELOG solo si contrato aprobado cambia.
- **Secuencia:** T01 y T02–T04 completadas. T05 queda parcialmente bloqueada por el cambio contractual de Q-24 y las pruebas de cola que dependen de él.

## WP-BE-50-T03 — Alinear detalle de pedido cliente

- **Estado/prioridad/tipo:** Completada (2026-10-13) · P1 · implementación/contrato.
- **Objetivo/evidencia:** `OrdersController.getOrder` devuelve mapa resumido; `GET /orders/{orderId}` exige `Order` completo.
- **Leer:** método y schema `Order`/`OrderItem`/opciones/timestamps en OpenAPI; dominio `Order`, `OrderItem`, snapshots; reglas de propiedad por sesión y errores catalogados.
- **Alcance/plan:** verificar campos persistidos y reconstrucción, mapear DTO completo, validar que orderId pertenezca a la sesión activa autenticada, añadir contrato/API e integración.
- **Fuera:** no modificar estado ni inventar datos históricos; no relajar `404` de pedido ajeno; contrato solo con aprobación.
- **Aceptación:** JSON contractual con versión, snapshots y timestamps realmente persistidos; autorización/tenant probada; historial ausente se representa según contrato, no se rellena ficticiamente. Entregar al T04.
- **Evidencia (2026-10-13):** respuesta completa cotejada con OpenAPI, timestamps nulos omitidos sin reconstrucción y alcance por sesión conservado. El `404 ORDER_NOT_FOUND` definido por OpenAPI/catálogo no existía en `ErrorCode`; se agregó a la implementación sin cambiar el contrato. `IdentityIntegrationTest` valida campos, snapshots, timestamps guardados, UUID inválido y pedido de otra sesión.

## WP-BE-50-T04 — Alinear listado staff y paginación

- **Estado/prioridad/tipo:** Completada (2026-10-13) · P1 · implementación/seguridad/contrato.
- **Objetivo/evidencia:** `StaffOrdersController.listStaffOrders` devuelve lista de mapas sin límite/cursor ni detalles; OpenAPI espera `StaffOrderPage` con `StaffOrder`, filtros `status`, `sessionId`, `limit`, `cursor`.
- **Leer:** controller + `StaffOrderPage`/`StaffOrder` OpenAPI; identidad/autorización, aislamiento restaurante/estación; test de integración identity y órdenes.
- **Alcance:** paginar establemente, reconstruir campos y preservar filtrado Cocina: solo ítems de sus estaciones y sin datos de participantes. Verificar propiedad por restaurantId y matriz permiso/rol.
- **Aceptación:** `nextCursor` y filtros contractuales correctos; FIFO/antigüedad conforme al contrato; ninguna fuga entre restaurante/estación; contrato e integración cubren página, filtros, acceso denegado. No cambiar esquema contractual por conveniencia.
- **Secuencia:** tras T03; no paralelizar cambios en `StaffOrdersController`.
- **Evidencia (2026-10-13):** `StaffOrderPage` completo; filtros CSV de estado/sesión, límite 1–100, cursor keyset ascendente `(created_at,id)`, aislamiento restaurantId, resultados vacíos paginados y filtro de ítems/participantes de Cocina. Aserciones focalizadas cubren desempate por UUID, filtros, paginación, aislamiento entre restaurante/estación y errores de status/limit/cursor.

## WP-BE-50-T05 — Puerta final del paquete

- **Estado/prioridad/tipo:** Implementación en validación · P1 · cierre/validación.
- **Leer:** tareas T01–T04 cerradas; [`estrategia de pruebas`](../../06-pruebas/estrategia.md); [`backend.md`](../backend.md); [`seguridad`](../../03-arquitectura/seguridad.md); V10/V11.
- **Ejecutar:** Redocly fijado por CI; pruebas API/contrato; suite `test`, `jacocoTestReport`, `check` con Postgres/Testcontainers; revisión de acciones, lock/version CAS, auditoría atómica, tenant/estación, FIFO/cursor/SLA e idempotencia. `git diff --check`.
- **Aceptación:** todas las acciones y respuestas del roadmap conformes; Q-24 resuelta/implementada; pruebas de integración ejecutadas satisfactoriamente con Docker; sin regresiones y JaCoCo ≥ umbral vigente (90 % según último registro); docs sincronizados. Reportar errores y resultados reales, sin declarar cerrado si falta una puerta.
- **Fuera:** no incorporar lógica monetaria paralela. WP-BE-73 debe anular cortesía si la cancelación de ítem lo requiere, mediante puerto compartido de billing/orders.
- **Habilita:** WP-BE-51 y dependientes declarados en roadmap.
- **Validación de Q-24 (2026-10-13):** Redocly ✅; Markdownlint ✅ (7 documentos, 0 issues); `GetStationQueueTest` ✅ (8 tests); compilación de producción y tests ✅. `check jacocoTestReport` no completa: 288 tests detectados, 2 inicializaciones de suites fallaron porque Docker no está disponible (Testcontainers); las 286 pruebas ejecutadas pasan, pero no se valida la integración nueva ni JaCoCo. `git diff --check` ✅.
- **Estado actual:** Q-24 y contrato resueltos; T05 sigue abierta por la integración/Testcontainers y la puerta de JaCoCo. No habilitar WP-BE-51 todavía.
- **Validación anterior a la resolución de Q-24 (2026-10-13; no valida estos cambios):** `.\backend\gradlew.bat -p backend test --tests "com.somphie.identity.api.IdentityIntegrationTest" --tests "com.somphie.preparation.application.GetStationQueueTest"` ✅ (24/24; 19 + 5); `.\backend\gradlew.bat -p backend check jacocoTestReport` ✅ (311/311; JaCoCo 2786/3007 = 92,65 %); markdownlint-cli2 0.23.3 sobre 5 documentos ✅; `git diff --check` ✅. OpenAPI no cambió en esa corrida; sus resultados no sustituyen la validación actual.
