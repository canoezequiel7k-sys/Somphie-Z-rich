# Índice de trabajo del backend

**Estado:** ejecución activa; rama `feat/backend-preparation`, HEAD `03d2b3b` (worktree sin commit). **Revisión:** 2026-10-14.

### Actualización D-160 (estado vigente)

| Bloque | Estado ahora |
|---|---|
| Q-26/27/31/32/33/34 y Fase 9 | Alcance aprobado por el dueño; ver [decisiones](./decisiones.md). Fórmulas Q-26 y contratos de identidad Fase 9 no se inventan. |
| WP-ARQ-60 | ADR-0006 aprobada: PostgreSQL outbox + SSE; implementación aún en curso. |
| WP-BE-60 | Parcial: V15, outbox transaccional, cursor/versión y productores de pedido cliente/catálogo; faltan endpoint SSE, retención y otros productores. |
| WP-BE-52 | Parcial: V16, disponibilidad de producto/opción, ETag persistente, auditoría y `catalog.updated` atómicos. |
| WP-BE-51-T01 | Parcial: Q-31 resuelta; faltan eventos de flujos restantes y validación final de tablero. |
| WP-BE-71-T01 / WP-BE-72 / WP-BE-80/81/82 / WP-BE-90/91 | Aprobaciones remueven bloqueos de decisión; implementación y contratos específicos siguen pendientes. |
| Validación (2026-10-14) | `check jacocoTestReport --no-daemon`: 371 tests, 0 fallos/errores/omitidos, 94,44 % (5010/5305 líneas); Markdownlint 9 docs y `git diff --check` pasan. OpenAPI no cambió. |

El mapa detallado que sigue es una foto histórica previa a D-160; sus bloqueos por aprobación ya no están vigentes.

## Cómo usar este plan

1. Elegí una ficha habilitada en la tabla y empezá por ella; no leas todas las fichas.
2. Seguí el prompt y la entrega de [`README.md`](../08-agentes/backend-work/README.md).
3. La ficha enlaza solo las fuentes necesarias. Las decisiones y el contrato oficiales prevalecen.
4. Los estados son una lectura documental y de código a la fecha indicada; antes de ejecutar, verificá que no haya cambiado Git ni se haya cerrado la tarea.

## Mapa de paquetes

| Orden | Paquete / estado observado | Evidencia y bloqueo | Tareas / dependencia oficial | Frontend y cierre |
|---|---|---|---|---|
| 1 | **WP-BE-40 — Implementada y validada (2026-10-13)** | `check jacocoTestReport`: 322 tests, 0 fallos; JaCoCo 93,02 %. Integración PostgreSQL cubre TTL, pedidos con actividad, rate limit y carreras entre join/expiración/limpieza y decisiones/preparación. | [Ficha WP-BE-40](../08-agentes/backend-work/wp-be-40.md); WP-BE-21, WP-BE-10; RN-SES-08, RN-ACC-07. | WP-BE-51 aún requiere el saldo completo de billing. |
| 2 | **WP-BE-50 — Completado (2026-10-13)** | Q-24 resuelta; OpenAPI 0.3.0 define FIFO/SLA por `acceptedAt`, históricos nullable y keyset eventual. `check jacocoTestReport`: 315 tests, 0 fallos; JaCoCo 92,81 %. | [T01–T05](../08-agentes/backend-work/wp-be-50.md); WP-BE-44. Cierre respaldado por validación backend y documental. | WP-BE-51 requiere saldo completo de billing. |
| 3 | **WP-BE-51 — T01 parcial; T02 completada** | Tablero y detalle implementados; llamados persisten en V14, con autorización tenant, ciclo de vida, expiración e integración PostgreSQL. Q-31 deja pendiente el `displayStatus` ante saldo negativo; avisos durables dependen de WP-BE-60/WP-ARQ-60. | [T01–T02](../08-agentes/backend-work/wp-be-51-52.md); el puerto de saldo de WP-BE-70 ya está validado. | No inventar el `displayStatus` negativo ni consumir `BillableChargesPort` como saldo. |
| 4 | **WP-BE-52 — Bloqueado por Q-27** | No hay handlers `PATCH`; `catalogVersion` es constante y no existe `event_log`/publisher para el evento contractual. | [T01](../08-agentes/backend-work/wp-be-51-52.md); WP-BE-30 + respuesta Q-27. | No tocar disponibilidad parcialmente; implementar versión, auditoría y evento de manera coherente. |
| 5 | **WP-BE-60 — Propuesta de spike lista; bloqueado por aprobación WP-ARQ-60** | No hay módulo `notifications`, `event_log`, publisher ni endpoint SSE; `audit_event` no es un outbox. ADR-0006 compara opciones y propone PostgreSQL durable + SSE por cursor; falta aprobar orden/cursor, versiones y reautorización. | [T01](../08-agentes/backend-work/wp-be-60.md); WP-ARQ-60 y WP-BE-50. | No implementar infraestructura hasta decisión ARQ. |
| 6 | **WP-BE-70 — T01/T02/T03 completadas y validadas en la rama** | `check jacocoTestReport`: 357 tests, 0 fallos; JaCoCo 93,96 % (4544/4836 líneas). PostgreSQL valida cuenta completa, reembolsos, anulaciones e interacción de propinas. `SessionBillPort` entrega el saldo derivado a floor; ADR-0010 referencia fuentes aprobadas. | [T01–T03](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-50 y WP-BE-73-T02. | WP-BE-51-T01 conserva Q-31 y la dependencia de eventos de WP-BE-60. |
| 7 | **WP-BE-71 — T01 parcial** | Cierre normal y forzado integrados con Orders/Billing; PostgreSQL valida cancelaciones, devolución automática del principal sin propina, deuda restante, rollback, replay, permisos, tenant y concurrencia. Suite: 365 tests, 0 fallos; JaCoCo 94,29 % (4826/5118). El pedido staff queda bloqueado por Q-34. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y Q-34. | Definir atribución del pedido staff al participante antes de implementar el endpoint; no inferirla del HOST ni crear participantes implícitos. |
| 8 | **WP-BE-72 — Semántica base contractual; renderer bloqueado por Q-32** | OpenAPI ya define PDF para sesión cerrada y RN-BIL-09/D-136 fijan contenido y leyenda. No hay renderer ni ADR de PDF; la referencia ADR-0009 en ficha era errónea. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-71/WP-UX-70 y Q-32. | No sumar dependencia ni definir ciclo de vida del archivo antes de aprobación. |
| 9 | **WP-BE-73 — T01 parcial; T02 completada y validada en la rama** | PostgreSQL valida propinas online/efectivo, tope y configuración, propina sola, atomicidad, replay y saldo; T02 cubre permiso/tenant/concurrencia de ajustes. Suite: 357 tests, 0 fallos; JaCoCo 93,96 % (4544/4836 líneas). | [T01–T02](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y WP-ARQ-70. | T01 conserva comprobante (Q-32) y reportes (Q-26; WP-BE-72/81). |
| 10 | **WP-BE-80 — Parcial** | Hay lectura de catálogo/media y dominio floor; falta CRUD administrativo. La API no define rutas CRUD admin. Q-26 bloquea implementación contractual. | [T01–T03](../08-agentes/backend-work/wp-be-80-82.md); WP-ARQ-80 y resolución de Q-26. | UI mockeable; no integrar admin hasta aprobación del contrato. |
| 11 | **WP-BE-81 — Propuesta de alcance lista; bloqueado por Q-26/Q-32** | Roadmap/UX enumeran informes; RN-TIP-08 solo fija propinas separadas y sin atribución laboral. Faltan operaciones, filtros, permisos y fórmulas; PDF depende de Q-32. | [T01–T02](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-71 y contrato aprobado. | No crear rutas, métricas ni exportaciones hasta aprobar definiciones y OpenAPI. |
| 12 | **WP-BE-82 — Parcial (catálogo base)** | Opciones/traducciones se leen en catálogo, pero no hay API administrativa; Q-26. | [T01](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-80 y contrato aprobado. | Mockeable; integrar tras contrato CRUD y pruebas de consistencia de asignaciones. |
| — | **Validación final — Pendiente** | Ninguna validación final acumulada para el conjunto 40–82. La validación de WP-BE-40/50 es específica de esos paquetes. | [T01](../08-agentes/backend-work/final-validation.md); todos los paquetes requeridos cerrados. | Habilita declarar backend central listo; Fase 9 no se presume obligatoria. |

Los estados reflejan la presencia y brechas observadas, no una aprobación de DoD. No se localizaron especificaciones WP-BE independientes previas a este plan; el alcance funcional sigue en el roadmap, OpenAPI y documentos de dominio. El plan creó **23 fichas**: 22 tareas de trabajo y una puerta de validación.

## Orden, dependencias y paralelización

| Trabajo | Dependencias/bloqueos | Paralelo seguro | Conflicto y límite |
|---|---|---|---|
| WP-BE-50 T01 → T02 → T03 → T04 → T05 | T01/Q-24 y T05 cerradas; paquete validado con contrato 0.3.0 y pruebas integradoras. | **No** entre T02–T04: comparten controladores/modelos/respuestas. | Un agente por vez sobre `OrdersController`, `StaffOrdersController` y OpenAPI. |
| WP-BE-51, WP-BE-52 | 51: BE-50 + BE-40 + saldo de BE-70 validados; llamadas y tablero ya tienen persistencia/consultas. Q-31 y publicación de eventos siguen pendientes. 52: BE-30 + resolución Q-27 sobre versionado/evento. | 52 es independiente de BE-50, pero bloqueada por Q-27; 51 conserva pendientes contractuales y de WP-BE-60. | `SessionBillPort` provee el resumen; no inventar estado negativo ni duplicar saldo. |
| WP-BE-60 | WP-ARQ-60 revisa spike y acepta/reemplaza ADR-0006; BE-50 ya está completada. | No implementar tabla/publisher parcial. `audit_event` no es outbox. | Acordar cursor sin pérdida, versionado de agregados, autenticación/revocación y presupuesto de DB/conexiones. |
| WP-BE-70 → 71 → 72; BE-73 | BE-70 validada; BE-71 habilita recibo; WP-BE-73-T01 requiere completar recibo/reportes. | Contenido PDF contractual está definido; Q-32 bloquea renderer/ciclo de vida. Q-26 bloquea reportes. | Sin sumar motor PDF ni fórmulas antes de aprobación. |
| WP-BE-80 → 82; WP-BE-81 | Contratos admin/reportes pendientes (Q-26); BE-81 requiere BE-71; export PDF requiere Q-32; BE-82 requiere BE-80. | Solo propuesta documental hasta acordar contratos. | Definir rutas, filtros, permisos, fórmulas, timezone y exportación antes de implementación. |
| Validación final | BE-50…82 requeridos cerrados y contratos/documentos sincronizados. | No; es una puerta integradora. | No esconder fallas por cambios de umbral/exclusiones. |

Dependencias de fase y entregables se citan en [`roadmap-fases.md`](../08-agentes/roadmap-fases.md). La tabla de actualización D-160 al inicio refleja el estado vigente; esta sección conserva decisiones históricas.

## Frontend: inicio e integración progresiva

- Web/Android pueden avanzar **ahora** con OpenAPI, Prism/MSW o repositorios fake: el roadmap no exige esperar a completar el backend. Los directorios `web/` y `android/` existen, pero en esta tarea no se inspeccionó su madurez interna; confirmarla antes de asignar implementación.
- Integrar funcionalidad por endpoint cuando pase contrato (`swagger-request-validator`), autorización/aislamiento y pruebas de integración aplicables. No considerar una ruta “lista” por existir en OpenAPI o en un controlador.
- Puertas iniciales: pedidos/cola tras BE-50; tablero tras BE-51; disponibilidad tras BE-52; cuenta tras BE-70; SSE tras BE-60; administración/reportes tras Q-26 y BE-80/81/82.
- Fase 9 (BE-90/91) está seleccionada por D-160; su implementación queda sujeta a reglas/contratos concretos. El retiro por mostrador de BE-92 sigue opcional.
- Despliegue/portfolio de Fase 10 es una salida posterior distinta de que los contratos locales estén listos para integración.

## Fase 9 — Extras seleccionados (implementación condicionada a contratos)

| Paquete | Alcance de roadmap | Estado de planificación / dependencia |
|---|---|---|
| WP-BE-90 | Registro/verificación/login de cliente, vincular participante y email | Fase 9 seleccionada por D-160; definir contrato y procesos de identidad según reglas aprobadas, sin inventar proveedor/estados. |
| WP-BE-91 | Email de comprobante y encuesta | Seleccionado dentro de Fase 9; depende de BE-90 y de configuración/integración de correo definida. |
| WP-BE-92 | Llamar al mozo; retiro por mostrador solo si se mantiene | Llamados ya tienen rutas/persistencia; falta productor durable `waiter.*` en WP-BE-60. El retiro por mostrador sigue opcional. |

## Siguiente paso (foto histórica anterior a D-160)

La secuencia histórica de abajo quedó superada. Próximo trabajo vigente: completar WP-BE-60 (SSE, retención, permisos y productores restantes), después terminar los paquetes dependientes y avanzar Fase 9 solo con reglas/contratos concretos.

## Documentos fuente

- Reglas generales y prioridad: [`AGENTS.md`](../../AGENTS.md).
- Plan/estado: [`advances.md`](advances.md), [`roadmap-fases.md`](../08-agentes/roadmap-fases.md).
- Decisiones/Q: [`decisiones.md`](decisiones.md).
- Contrato vigente: [`openapi.yaml`](../04-api/openapi.yaml), [`CHANGELOG.md`](../04-api/CHANGELOG.md).
- Dominio: [`estados-y-transiciones.md`](../02-dominio/estados-y-transiciones.md), [`reglas-negocio.md`](../02-dominio/reglas-negocio.md), [`modelo-datos.md`](../02-dominio/modelo-datos.md), [`catálogo de errores`](../02-dominio/catalogo-errores.md).
- Arquitectura y validación: [`backend-arquitectura.md`](../03-arquitectura/backend-arquitectura.md), [`estrategia de pruebas`](../06-pruebas/estrategia.md), [`guía de trabajo`](../08-agentes/backend-work/README.md).

## Continuidad

Cada ficha ordena registrar evidencia, pruebas ejecutadas (comando + resultado), bloqueos/decisiones, archivos y siguiente tarea en `advances.md`. Marcar como cerrado solo con sus criterios de aceptación verificados; no crear commits ni push sin pedido explícito.
