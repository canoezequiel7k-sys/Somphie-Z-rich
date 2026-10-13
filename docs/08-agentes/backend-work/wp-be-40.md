# WP-BE-40 — Sesiones de mesa

Fuente de alcance: [roadmap Fase 4](../roadmap-fases.md), [RN-SES-08](../../02-dominio/reglas-negocio.md), [casos límite CL-027/040/042](../../02-dominio/casos-limite.md), catálogo de errores y OpenAPI vigente.

## Estado y alcance

- **Estado:** implementada y validada (2026-10-13).
- **Responsabilidad:** resolver/open/join de mesa, participantes y token hasheado, limitador de intentos de join code, registro de actividad y expiración programada de sesiones vacías.
- **Fuera de alcance:** expiración de pedidos (WP-BE-42), sesiones con actividad o pedidos pendientes/facturables, métricas y cambios de contrato.

## Decisiones ya fijadas por el dominio

- `session_idle_ttl_min` se aplica por restaurante; RN-SES-08 determina que actividad incluye crear pedido, unirse y acciones del personal, pero no consultar ni hacer polling.
- RN-SES-08 solo permite expirar sin pedidos pendientes ni cargos/facturables. El job no cambia estados de pedidos ni sustituye el trabajo de expiración de WP-BE-42.
- RN-ACC-07/CL-042 fijan cinco fallos, ventana de diez minutos y bloqueo de diez minutos. Los errores `JOIN_CODE_INVALID` y `JOIN_CODE_RATE_LIMITED` ya existen en el contrato/catálogo.

## Implementación y motivos

- `ExpireEmptySessionsJob` corre cada 60 segundos por defecto. Una actualización condicional en PostgreSQL consulta el TTL del restaurante y cierra solo sesiones `OPEN` sin pedidos pendientes/facturables; la misma transacción registra `SESSION_EXPIRED`. Si hay varias instancias, solo la que actualiza la fila puede auditarla.
- Las operaciones que necesitan sesión y pedido adquieren locks en orden **sesión → pedido → ítems**. La limpieza de intentos vencidos corre en una transacción separada, bloquea candidatos con `SKIP LOCKED` y no toca sesiones; así no invierte el orden empleado por el join, que bloquea intento y luego sesión.
- V12 agrega el índice de actividad abierta y `join_code_attempt`, con hash SHA-256 de la IP y `table_id`; no almacena la IP en claro. Este hash **no anonimiza** la IP: el espacio IPv4 es enumerable y permite ataques por diccionario. La elección evita persistir la IP directa sin introducir una clave cuya gestión no está definida. Una futura HMAC requeriría decidir almacenamiento/rotación del secreto y cómo tratar intentos tras rotarlo.
- `SELECT ... FOR UPDATE` serializa intentos para el mismo par mesa/IP. El quinto fallo activa el bloqueo, que vence al cumplirse su instante límite.
- El mensaje de código inválido es uniforme; el bloqueo devuelve el código contractual y `Retry-After`, sin exponer cuántos intentos quedan. La IP se obtiene del socket (`remoteAddr`): no confiar en `X-Forwarded-For` hasta configurar un proxy de confianza.
- La unión válida, la creación de un pedido y las decisiones/acciones de preparación actualizan `last_activity_at` dentro de su transacción. Así, una carrera con el job queda serializada por PostgreSQL y un cierre previo impide la acción.
- El actor de auditoría `SYSTEM` usa UUID cero porque `audit_event.actor_id` exige UUID pero no tiene FK a una cuenta.

## Evidencia

- `BackendSmokeTest`: integraciones de límite exacto/expiración, intentos concurrentes, unión/actividad, TTL por restaurante, pedidos pendientes/facturables, expiraciones concurrentes y carrera entre join, expiración y limpieza.
- `IdentityIntegrationTest`: aceptación y rechazo concurrentes con preparación, estado/versionado coherente y actividad persistida; `ApplyItemActionTest` cubre el reloj de las acciones staff.
- `.\backend\gradlew.bat -p backend check jacocoTestReport`: ✅ 322 tests; 0 fallos, errores u omitidos; JaCoCo 2933/3153 líneas (93,02 %). Testcontainers/PostgreSQL incluidos.

## Consecuencias y precauciones

- El cierre puede ocurrir hasta un intervalo de scheduler después del TTL; el default es un minuto. El TTL en sí es específico del restaurante.
- En despliegues con proxy, la dirección IP vista por el socket puede ser la del proxy; configurar origen confiable antes de habilitar encabezados forwarded.
- Pruebas de integración eliminan únicamente restaurantes temporales creados por ellas para no contaminar el seed común.
- WP-BE-51 no debe comenzar hasta confirmar la fuente de `balanceMinor`; WP-BE-52 sigue bloqueada por Q-27.
