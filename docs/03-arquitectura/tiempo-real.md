# Tiempo real (Fase 6)

> Decisión base: **SSE** con eventos "pista" (ADR-0006), aprobada por D-160. WP-BE-60 está parcialmente implementada; los consumidores mantienen polling como fallback.
>
> Estado al 2026-10-15: V15/V17, endpoint SSE, cursor/reanudación, filtro tenant/sesión/estación y retención están implementados y probados en PostgreSQL. Pruebas HTTP reales cubren cursores malformados/futuros/vencidos, heartbeat a 25 s, replay, revocación y 50 streams HTTP/1.1 simultáneos con entrega común; tras revocación, las tareas del scheduler no superan la línea base y los executors cliente se cierran. La prueba no representa capacidad sostenida de producción; falta acordar perfil y medir antes de cerrar WP-BE-60.

Se usa PostgreSQL como log durable/outbox transaccional y fuente de replay SSE; un aviso post-commit en memoria solo optimiza el despertar de lectores. El cursor se asigna con contador bloqueado por restaurante hasta commit para ordenar los eventos visibles. Ver costos, pruebas y límites en [ADR-0006](../00-gobierno/adr/ADR-0006-tiempo-real-sse.md).

## 1. Estrategia por fases
| Fase | Mecanismo |
|---|---|
| 3–5 | REST + polling (web cliente: 5–10 s con ETag/`If-None-Match`; Android: 5 s en primer plano) |
| 6 | SSE con `event_log`, `Last-Event-ID`, heartbeat. Polling pasa a **fallback** |
| posterior | Push FCM para app cerrada (puerto `PushSender`) |

## 2. Principios
1. El evento **avisa**, la API **informa**: ante un evento el cliente reconsulta el recurso.
2. El backend sigue siendo la fuente de verdad (RN-RT-01).
3. Tolerar eventos tardíos o duplicados (deduplicar por `eventId`; usar `version`).
4. Tras reconectar, reconsultar estado y reanudar con `Last-Event-ID`.
5. Un fallo al emitir **no** revierte la operación (RN-RT-06).

## 3. Canal y alcance
| Stream | URL | Quién | Qué recibe |
|---|---|---|---|
| Sesión | `GET /events/stream?scope=session` (token de invitado) | Participantes | Eventos de **su** sesión |
| Staff | `GET /events/stream?scope=staff` (JWT) | Personal | Eventos del restaurante filtrados por permiso/estación |
| Admin | `GET /events/stream?scope=staff` | Dueño | Igual que staff (+ catálogo) |

- Autenticación por `Authorization: Bearer`. Como `EventSource` nativo no permite headers, la web usa `fetch` + lector de stream (o polyfill). Android usa OkHttp SSE.
- Heartbeat (comentario `:`) cada 25 s. Cliente reconecta con **backoff exponencial + jitter** (1s→30s).

## 4. Flujo de emisión
```mermaid
flowchart LR
  UC[Caso de uso en transacción] --> EVL[(event_log en la misma tx)]
  EVL -->|commit visible| POLL[Consulta durable por cursor]
  POLL --> SSE[Conexiones SSE<br/>filtradas por alcance]
  SSE --> CL[Clientes]
  CL -->|reconsulta| API[REST]
```
El evento se inserta en `event_log` **en la misma transacción**; el lector solo consulta filas confirmadas. No hay publisher en memoria como fuente de entrega: al desconectarse o reiniciarse, el cliente reanuda desde `Last-Event-ID`.

La retención borra en lotes los eventos de más de 48 horas y avanza un watermark por restaurante. Un cursor anterior al watermark recibe `410 EVENT_ID_TOO_OLD` al abrir o continuar el stream. Se revalida el acceso en cada consulta; el heartbeat sigue siendo comentario SSE cada 25 segundos.

## 5. Catálogo de eventos
Ver `04-api/eventos.md`.

## 6. Pruebas obligatorias (QA)
- Desconexión y reconexión con `Last-Event-ID` (sin perder ni duplicar efectos): prueba HTTP real implementada; cursores malformados/futuros se rechazan y los vencidos responden 410.
- Eventos duplicados y desordenados.
- `EVENT_ID_TOO_OLD` → refresco completo.
- Un participante **no** recibe eventos de otra sesión (`STREAM_SCOPE_FORBIDDEN`).
- Carga: la prueba HTTP abre 50 streams simultáneos y sincroniza los lectores antes de publicar una mutación durable. Verifica el `eventId` y tipo exactos en cada stream; revoca el participante y espera que las tareas no superen la línea base, cerrando cuerpos y executors con límites de espera. Es una prueba funcional/limpieza en Testcontainers, no un benchmark de capacidad sostenida.

## 7. Límites a recordar
- SSE actualiza interfaces **abiertas**; no despierta apps cerradas.
- Muchos proveedores gratuitos cortan conexiones ociosas: por eso el heartbeat y el fallback a polling.
