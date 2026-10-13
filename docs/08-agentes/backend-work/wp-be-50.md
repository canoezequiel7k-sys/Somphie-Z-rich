# Fichas de cierre WP-BE-50

Alcance madre: [`roadmap-fases.md`, Fase 5](../roadmap-fases.md). Contrato vigente 0.2.6; reglas/transiciones y Q-24 son autoridad. El código existente incluye `StaffPreparationController`, `StationQueue`, `JdbcStationQueueRepository`, `JdbcPreparationActionRepository`, `OrdersController` y `StaffOrdersController`; V10 conserva `queued_at` histórico nulo. La consulta de cola filtra `queued_at IS NOT NULL`; el cursor exige hora + UUID. No reconstruir horas.

Común a las fichas: empezar con `AGENTS.md`, [`advances.md`](../../00-gobierno/advances.md), [`decisiones.md` Q-24](../../00-gobierno/decisiones.md), [`openapi.yaml`](../../04-api/openapi.yaml) y tests nombrados. No implementar contabilidad de cortesías: pertenece a BE-73 e integra con cargo de pedidos.

## WP-BE-50-T01 — Aprobación contractual Q-24

- **Estado/prioridad/tipo:** Dirección principal aprobada por el propietario (2026-10-13); formalización contractual pendiente · P0 · decisión/contrato.
- **Objetivo/evidencia:** determinar exposición y orden de ítems activos cuyo `queued_at` histórico es NULL. Contrato actual exige `queuedAt` no nulo (`StationQueueItem`); SQL omite nulos y el cursor usa `(queuedAt,itemId)`. Impacta visibilidad, FIFO, cursores, `delayed`/SLA y métricas.
- **Leer:** [`Q-24`](../../00-gobierno/decisiones.md), OpenAPI `GET /staff/stations/{stationId}/queue` y `StationQueueItem`/`StationQueue`; `V10__preparation_order_snapshots.sql`; `JdbcStationQueueRepository.kt`, `StationQueue.kt`, `GetStationQueueTest.kt`; reglas §4 de [`estados-y-transiciones.md`](../../02-dominio/estados-y-transiciones.md).
- **Dirección aprobada:** incluir los históricos activos con `queued_at = NULL`, sin inventar timestamps ni sustituirlos por `accepted_at`.
- **Propuesta de orden, no formalizada:** históricos primero por `item_id`; conocidos después por `queued_at ASC, item_id`. FIFO cronológico solo aplica a conocidos. `queuedAt` nullable, semántica de `delayed`/SLA, garantías del cursor/paginación concurrente y métricas agregadas siguen pendientes; no asumir `delayed: null`.
- **Trabajo restante:** formalizar esos detalles con Arquitectura/dueño y actualizar contrato/CHANGELOG según la decisión antes de tocar código. Luego probar históricos, nuevos, desempates, paginación mixta, SLA y estados terminales. No iniciar T02 mientras siga abierta esta formalización.
- **Estado T01:** Cerrada (2026-10-13): propietario aprobó la dirección principal y se registraron los límites. La formalización contractual restante bloquea solo cambios de cola que dependan de esos detalles; no bloquea T02, que no modifica la cola.
- **No paralelo:** tareas que alteren la representación del endpoint/cola esperan su formalización contractual.

## WP-BE-50-T02 — Alinear lista de pedidos de sesión

- **Estado/prioridad/tipo:** En curso (2026-10-13) · P1 · implementación + pruebas contractuales.
- **Objetivo/evidencia:** `OrdersController.listSessionOrders` hoy devuelve `List<Map<...>>` con seis campos; OpenAPI exige `OrderPage` (`items`, `nextCursor`) de `Order` completos. Faltan versionado/ítems y cursor contractual.
- **Leer:** método mencionado; `OrderPage`/`Order` en OpenAPI y criterios AC-CU-07/08; `Order`, `OrderItem`, pruebas de Orders existentes (`IdentityIntegrationTest`/`BackendSmokeTest` solo como referencias, confirmar cobertura).
- **Alcance:** DTO/mapeo, consulta eficiente/paginación/cursor, autorización por sesión, tests API+contrato e integración Postgres donde haga falta. Primero cotejar todos los campos obligatorios opcionales y cómo serializa historial.
- **Fuera:** no cambiar OpenAPI para aceptar el mapa simplificado sin aprobación ARQ; no modificar creación ni cancelación de pedido.
- **Aceptación:** respuesta de éxito pasa esquema contractual; orden más reciente primero; cursor estable/limit; nunca filtra otras sesiones; pruebas de casos vacío, página siguiente y recurso no propio. Actualizar CHANGELOG solo si contrato aprobado cambia.
- **Secuencia:** T01 cerrada; ejecutar T02 ahora. Antes de T03/T04 (comparten controlador/modelos). Entregar pruebas y brechas restantes.

## WP-BE-50-T03 — Alinear detalle de pedido cliente

- **Estado/prioridad/tipo:** Pendiente tras T02 · P1 · implementación/contrato.
- **Objetivo/evidencia:** `OrdersController.getOrder` devuelve mapa resumido; `GET /orders/{orderId}` exige `Order` completo.
- **Leer:** método y schema `Order`/`OrderItem`/opciones/timestamps en OpenAPI; dominio `Order`, `OrderItem`, snapshots; reglas de propiedad por sesión y errores catalogados.
- **Alcance/plan:** verificar campos persistidos y reconstrucción, mapear DTO completo, validar que orderId pertenezca a la sesión activa autenticada, añadir contrato/API e integración.
- **Fuera:** no modificar estado ni inventar datos históricos; no relajar `404` de pedido ajeno; contrato solo con aprobación.
- **Aceptación:** JSON contractual con versión, snapshots y timestamps realmente persistidos; autorización/tenant probada; historial ausente se representa según contrato, no se rellena ficticiamente. Entregar al T04.

## WP-BE-50-T04 — Alinear listado staff y paginación

- **Estado/prioridad/tipo:** Pendiente tras T03 · P1 · implementación/seguridad/contrato.
- **Objetivo/evidencia:** `StaffOrdersController.listStaffOrders` devuelve lista de mapas sin límite/cursor ni detalles; OpenAPI espera `StaffOrderPage` con `StaffOrder`, filtros `status`, `sessionId`, `limit`, `cursor`.
- **Leer:** controller + `StaffOrderPage`/`StaffOrder` OpenAPI; identidad/autorización, aislamiento restaurante/estación; test de integración identity y órdenes.
- **Alcance:** paginar establemente, reconstruir campos y preservar filtrado Cocina: solo ítems de sus estaciones y sin datos de participantes. Verificar propiedad por restaurantId y matriz permiso/rol.
- **Aceptación:** `nextCursor` y filtros contractuales correctos; FIFO/antigüedad conforme al contrato; ninguna fuga entre restaurante/estación; contrato e integración cubren página, filtros, acceso denegado. No cambiar esquema contractual por conveniencia.
- **Secuencia:** tras T03; no paralelizar cambios en `StaffOrdersController`.

## WP-BE-50-T05 — Puerta final del paquete

- **Estado/prioridad/tipo:** Pendiente · P1 · cierre/validación.
- **Leer:** tareas T01–T04 cerradas; [`estrategia de pruebas`](../../06-pruebas/estrategia.md); [`backend.md`](../backend.md); [`seguridad`](../../03-arquitectura/seguridad.md); V10/V11.
- **Ejecutar:** Redocly fijado por CI; pruebas API/contrato; suite `test`, `jacocoTestReport`, `check` con Postgres/Testcontainers; revisión de acciones, lock/version CAS, auditoría atómica, tenant/estación, FIFO/cursor/SLA e idempotencia. `git diff --check`.
- **Aceptación:** todas las acciones y respuestas del roadmap conformes; Q-24 resuelta/implementada; pruebas de integración ejecutadas satisfactoriamente con Docker; sin regresiones y JaCoCo ≥ umbral vigente (90 % según último registro); docs sincronizados. Reportar errores y resultados reales, sin declarar cerrado si falta una puerta.
- **Fuera:** no incorporar lógica monetaria paralela. WP-BE-73 debe anular cortesía si la cancelación de ítem lo requiere, mediante puerto compartido de billing/orders.
- **Habilita:** WP-BE-51 y dependientes declarados en roadmap.
