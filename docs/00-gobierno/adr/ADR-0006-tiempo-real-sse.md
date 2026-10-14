# ADR-0006: Tiempo real con SSE
**Estado:** aprobada por el dueño (D-160, 2026-10-14); implementación WP-BE-60 pendiente.

## Contexto
Los eventos fluyen servidor → cliente. Las acciones del cliente ya usan REST.

## Decisión
**Server-Sent Events** con eventos "pista" (`EV-*`): llevan `type`, `aggregateId`, `version`; el cliente **vuelve a consultar** el recurso. Tabla `event_log` con ID creciente → `Last-Event-ID` permite reanudar. Heartbeat cada 25 s. Si el ID es demasiado viejo (410) el cliente refresca todo.

## Spike WP-ARQ-60 — propuesta técnica

### Problema y consumidores
REST sigue siendo la fuente de verdad, pero las pantallas abiertas necesitan enterarse de cambios sin depender del polling continuo. Web, Android y el tablero staff consumen eventos mínimos como pistas; al recibir uno vuelven a consultar REST. Los productores previstos son Orders/Preparation, Floor, Billing y Catalog. No es un mecanismo de comandos, auditoría ni notificación a una app cerrada.

### Separación de auditoría y eventos
`audit_event` (V7) es un registro de quién hizo qué, con actor, motivo y resúmenes; satisface trazabilidad y sus reglas append-only. No tiene cursor numérico de replay ni el envelope del contrato. El futuro `event_log` registra cambios que los clientes pueden reconsultar, con retención y filtrado por stream. No reemplaza ni debe escribirse en lugar de la auditoría exigida; una operación puede requerir ambos registros en la misma transacción.

### Alternativas evaluadas
| Opción | Ventaja | Riesgo/costo |
|---|---|---|
| Insertar el evento en PostgreSQL dentro de la transacción y servir SSE leyendo ese log por cursor | Atomicidad, replay durable y varias instancias sin infraestructura nueva; el polling DB recupera desconexiones | Consultas periódicas por conexión; exige índices, límites de conexiones, retención y una estrategia correcta de cursor |
| Publicar solo con evento Spring `AFTER_COMMIT`/canal en memoria | Baja latencia y simple en una instancia | No es durable; caída entre commit y publicación o reinicio pierde la notificación; varias instancias no comparten suscriptores |
| PostgreSQL `LISTEN/NOTIFY` más `event_log` | Despierta lectores con baja latencia y el log permite replay | `NOTIFY` no es durable y requiere conexiones dedicadas; debe conservarse polling de recuperación y el costo de conexión |
| Broker externo | Escala y desacopla consumidores | Fuera del alcance MVP; añade servicio, operación, reintentos y configuración |

### Arquitectura aprobada
Mantener PostgreSQL como única infraestructura: insertar cada evento pista en `event_log` dentro de la misma transacción del cambio; que los streams consulten el log por `id > Last-Event-ID`, con filtro SQL de tenant/sesión/permisos y heartbeat contractual. Un aviso local `AFTER_COMMIT` solo podría despertar antes la consulta; nunca sería fuente ni garantía de entrega. No incorporar `LISTEN/NOTIFY` hasta medir que el polling incumple el presupuesto de conexiones/carga.

La fila de negocio y el evento se confirman o revierten juntos. La difusión SSE ocurre después del commit y puede repetirse; el consumidor deduplica por `eventId`. El fallo de un stream no revierte el cambio ni requiere reintentar una operación de negocio. La recuperación consulta el log desde el cursor y, tras 48 h o cursor fuera de retención, devuelve el 410 contractual para refresco REST.

### Cursor, orden e idempotencia — condición técnica
`bigserial` por sí solo no garantiza orden de commit. La aprobación fija un contador transaccional bloqueado por restaurante hasta commit; eso ordena eventos visibles dentro de cada stream. Un rollback puede dejar sin consumir el valor tentativo, pero no publicar un evento. El costo de serializar escritores por restaurante debe medirse con las pruebas de concurrencia y carga.

La clave idempotente de negocio y sus transiciones evitan crear un segundo evento en replay sin mutación. No agregar una unicidad genérica sobre payload: dos eventos iguales pueden representar mutaciones intencionales distintas. Cada agregado tiene una versión persistida monotónica que aumenta en la misma transacción que la mutación y el evento, incluso si el modelo de dominio no exponía antes un campo `version`.

### Seguridad, operación y pruebas del spike
- Reautorizar cada consulta/entrega: `scope=session` queda limitado al `sessionId` del guest token; `scope=staff` al `restaurantId` y permisos (y estación cuando el evento lo requiere). Nunca confiar en IDs del cliente ni filtrar solo después de leer payloads.
- Guardar envelope mínimo (`type`, aggregate, version, scope, hora), sin datos personales, motivos, precios ni credenciales. La consulta no expone filas de otro tenant.
- Migración aditiva: tabla `event_log`, índices por cursor y scope, y borrado por lotes con `created_at < now() - interval '48 hours'`. Revisar `EXPLAIN`, índices/volumen, conexiones SSE y política de backpressure; heartbeat 25 s.
- Pruebas PostgreSQL: rollback del negocio no deja evento; commit sí; replay de Idempotency-Key no duplica; mutaciones concurrentes no rompen cursor/orden; reanudación y límite 48 h; filtrado de tenant/sesión/estación y permiso. Luego probar desconexión, heartbeat, 50 streams y caída/reinicio en integración.

### Seguridad y límites aprobados (D-160)
El stream se filtra en la consulta SQL por restaurante y por sesión o estación según el alcance; los permisos se vuelven a validar al leer/entregar eventos y durante el heartbeat para detectar revocaciones. No se exponen payloads sensibles. El ADR aprueba la arquitectura, no declara WP-BE-60 implementada: hacen falta migración, productores transaccionales, endpoint, retención, pruebas de seguridad/cursor/reconexión y validación operativa.

## Alternativas
WebSocket/STOMP (bidireccional innecesario, más complejidad para un principiante), long polling (más carga), push FCM (solo para app cerrada, fase posterior).

## Consecuencias
Android usa OkHttp-SSE; web usa `EventSource` (o `fetch` stream si se necesita header `Authorization`). Fallback a polling si SSE falla. Si en Fase 6 se descubre un requisito que SSE no cubre, se reemplaza esta ADR por una STOMP.
