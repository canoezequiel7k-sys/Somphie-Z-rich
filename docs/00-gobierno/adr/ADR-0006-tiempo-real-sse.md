# ADR-0006: Tiempo real con SSE
**Estado:** propuesta (D-026, D-118). Se confirma al iniciar Fase 6.

## Contexto
Los eventos fluyen servidor → cliente. Las acciones del cliente ya usan REST.

## Decisión
**Server-Sent Events** con eventos "pista" (`EV-*`): llevan `type`, `aggregateId`, `version`; el cliente **vuelve a consultar** el recurso. Tabla `event_log` con ID creciente → `Last-Event-ID` permite reanudar. Heartbeat cada 25 s. Si el ID es demasiado viejo (410) el cliente refresca todo.

## Alternativas
WebSocket/STOMP (bidireccional innecesario, más complejidad para un principiante), long polling (más carga), push FCM (solo para app cerrada, fase posterior).

## Consecuencias
Android usa OkHttp-SSE; web usa `EventSource` (o `fetch` stream si se necesita header `Authorization`). Fallback a polling si SSE falla. Si en Fase 6 se descubre un requisito que SSE no cubre, se reemplaza esta ADR por una STOMP.
