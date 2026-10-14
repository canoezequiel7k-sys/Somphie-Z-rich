# Índice de trabajo del backend

**Estado:** plan de ejecución; el estado de cada paquete figura en la tabla. **Última revisión:** rama `feat/backend-preparation`, HEAD `75b567c`; WP-BE-51-T01 avanzó localmente con llamados persistidos y tablero staff, sin commit; WP-BE-40 está publicada y WP-BE-50 está respaldada por `1ed84fd`.

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
| 3 | **WP-BE-51 — T01 parcial; T02 completada** | Tablero y detalle implementados; llamados persisten en V14, con autorización tenant, ciclo de vida, expiración e integración PostgreSQL. Q-31 deja pendiente el `displayStatus` ante saldo negativo; eventos durables siguen en WP-BE-60. | [T01–T02](../08-agentes/backend-work/wp-be-51-52.md); el puerto de saldo de WP-BE-70 ya está validado. | No inventar el `displayStatus` negativo ni consumir `BillableChargesPort` como saldo. |
| 4 | **WP-BE-52 — Bloqueado por Q-27** | No hay handlers `PATCH`; `catalogVersion` es constante y no existe `event_log`/publisher para el evento contractual. | [T01](../08-agentes/backend-work/wp-be-51-52.md); WP-BE-30 + respuesta Q-27. | No tocar disponibilidad parcialmente; implementar versión, auditoría y evento de manera coherente. |
| 5 | **WP-BE-60 — Investigación completada; bloqueado por WP-ARQ-60** | No hay módulo `notifications`, `event_log`, publisher ni endpoint SSE; `audit_event` no es un outbox. ADR-0006 sigue en propuesta; falta el spike y aprobación/reemplazo. | [T01](../08-agentes/backend-work/wp-be-60.md); WP-ARQ-60 y WP-BE-50. | No implementar infraestructura parcial. Después de ARQ, integrar persistencia, eventos y SSE con pruebas de recuperación/alcance. |
| 6 | **WP-BE-70 — T01/T02/T03 completadas localmente** | `check jacocoTestReport`: 357 tests, 0 fallos; JaCoCo 93,96 % (4544/4836 líneas). PostgreSQL valida cuenta completa, reembolsos, anulaciones e interacción de propinas. `SessionBillPort` entrega el saldo derivado a floor; ADR-0010 referencia fuentes aprobadas. | [T01–T03](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-50 y WP-BE-73-T02. | WP-BE-51-T01 conserva Q-31 y la dependencia de eventos de WP-BE-60. |
| 7 | **WP-BE-71 — No iniciado** | No hay endpoints de cierre ni de pedido en nombre del cliente en backend. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70. | Mock permitido; integración real después de transacciones/estados auditados. |
| 8 | **WP-BE-72 — No iniciado** | El contrato tiene `/sessions/current/receipt.pdf`; no hay `PdfRenderer`/adaptador backend. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-71 y WP-UX-70; definir ADR de PDF antes del adaptador. | El cliente puede mockear descarga; conectar cuando PDF contractual esté probado. |
| 9 | **WP-BE-73 — T01 parcial; T02 completada localmente** | PostgreSQL valida propinas online/efectivo, tope y configuración, propina sola, atomicidad, replay y saldo; T02 cubre permiso/tenant/concurrencia de ajustes. Suite: 357 tests, 0 fallos; JaCoCo 93,96 % (4544/4836 líneas). | [T01–T02](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y WP-ARQ-70. | T01 conserva comprobante/reportes (RN-TIP-07/08; WP-BE-72/81). |
| 10 | **WP-BE-80 — Parcial** | Hay lectura de catálogo/media y dominio floor; falta CRUD administrativo. La API no define rutas CRUD admin. Q-26 bloquea implementación contractual. | [T01–T03](../08-agentes/backend-work/wp-be-80-82.md); WP-ARQ-80 y resolución de Q-26. | UI mockeable; no integrar admin hasta aprobación del contrato. |
| 11 | **WP-BE-81 — No iniciado** | No se encontró módulo `reporting` ni endpoints de informes/auditoría; Q-26 incluye este hueco de contrato. | [T01–T02](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-71 y contrato aprobado. | Mock de informes; integración después de contratos, queries y aislamiento verificados. |
| 12 | **WP-BE-82 — Parcial (catálogo base)** | Opciones/traducciones se leen en catálogo, pero no hay API administrativa; Q-26. | [T01](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-80 y contrato aprobado. | Mockeable; integrar tras contrato CRUD y pruebas de consistencia de asignaciones. |
| — | **Validación final — Pendiente** | Ninguna validación final acumulada para el conjunto 40–82. La validación de WP-BE-40/50 es específica de esos paquetes. | [T01](../08-agentes/backend-work/final-validation.md); todos los paquetes requeridos cerrados. | Habilita declarar backend central listo; Fase 9 no se presume obligatoria. |

Los estados reflejan la presencia y brechas observadas, no una aprobación de DoD. No se localizaron especificaciones WP-BE independientes previas a este plan; el alcance funcional sigue en el roadmap, OpenAPI y documentos de dominio. El plan creó **23 fichas**: 22 tareas de trabajo y una puerta de validación.

## Orden, dependencias y paralelización

| Trabajo | Dependencias/bloqueos | Paralelo seguro | Conflicto y límite |
|---|---|---|---|
| WP-BE-50 T01 → T02 → T03 → T04 → T05 | T01/Q-24 y T05 cerradas; paquete validado con contrato 0.3.0 y pruebas integradoras. | **No** entre T02–T04: comparten controladores/modelos/respuestas. | Un agente por vez sobre `OrdersController`, `StaffOrdersController` y OpenAPI. |
| WP-BE-51, WP-BE-52 | 51: BE-50 + BE-40 + saldo de BE-70 validados; llamadas y tablero ya tienen persistencia/consultas. Q-31 y publicación de eventos siguen pendientes. 52: BE-30 + resolución Q-27 sobre versionado/evento. | 52 es independiente de BE-50, pero bloqueada por Q-27; 51 conserva pendientes contractuales y de WP-BE-60. | `SessionBillPort` provee el resumen; no inventar estado negativo ni duplicar saldo. |
| WP-BE-60 | WP-ARQ-60 acepta o reemplaza ADR-0006 después del spike; BE-50 ya está completada localmente. | El spike/decisión ARQ precede; no implementar tabla o publisher parcial. La auditoría existente no sirve como outbox. | Una sola persona dueña de eventos, retención, recuperación y persistencia `event_log`. |
| WP-BE-70 → 71 → 72; BE-73 | ADR-0010 registra fuentes; T01–T03, reembolsos, cuenta y puerto de sesión están validados; T01 de propinas sigue por recibo/reportes. BE-71 depende del cierre validado de BE-70. | Resolver recibo/reportes de propinas antes de declarar WP-BE-73-T01 integral; WP-BE-70 queda localmente completa. | Locks, tablas y migraciones de billing son compartidos. No paralelizar migraciones sin coordinación. |
| WP-BE-80 → 82; WP-BE-81 | Contratos admin/reportes pendientes (Q-26); BE-81 requiere BE-71; BE-82 requiere BE-80. | Sin Q-26, solo investigación documental. Después, grupos distintos pueden diseñarse en paralelo con responsables de rutas separados. | Ordenar migraciones/versiones y reservar rutas/esquemas antes de ejecución concurrente. |
| Validación final | BE-50…82 requeridos cerrados y contratos/documentos sincronizados. | No; es una puerta integradora. | No esconder fallas por cambios de umbral/exclusiones. |

Dependencias de fase y entregables se citan en [`roadmap-fases.md`](../08-agentes/roadmap-fases.md); se sincronizó allí el avance parcial de BE-70/73. BE-51 sigue bloqueada hasta que pagos, reembolsos, ajustes y el puerto de saldo estén completos y validados.

## Frontend: inicio e integración progresiva

- Web/Android pueden avanzar **ahora** con OpenAPI, Prism/MSW o repositorios fake: el roadmap no exige esperar a completar el backend. Los directorios `web/` y `android/` existen, pero en esta tarea no se inspeccionó su madurez interna; confirmarla antes de asignar implementación.
- Integrar funcionalidad por endpoint cuando pase contrato (`swagger-request-validator`), autorización/aislamiento y pruebas de integración aplicables. No considerar una ruta “lista” por existir en OpenAPI o en un controlador.
- Puertas iniciales: pedidos/cola tras BE-50; tablero tras BE-51; disponibilidad tras BE-52; cuenta tras BE-70; SSE tras BE-60; administración/reportes tras Q-26 y BE-80/81/82.
- Fase 9 (BE-90/91/92) queda separada como extras seleccionados/condicionados del roadmap; Q-07 está resuelta. BE-92 limita retiro por mostrador a “si se mantiene”. No bloquea el inicio de frontend ni la puerta central de BE-50…82.
- Despliegue/portfolio de Fase 10 es una salida posterior distinta de que los contratos locales estén listos para integración.

## Fase 9 — Extras condicionados (fuera del conteo obligatorio)

| Paquete | Alcance de roadmap | Estado de planificación / dependencia |
|---|---|---|
| WP-BE-90 | Registro/verificación/login de cliente, vincular participante y email | Condicionado a seleccionar extras F9; Q-07 resuelta. Verificar contrato/estado antes de asignar. |
| WP-BE-91 | Email de comprobante y encuesta | Condicionado; depende de BE-90. No incluir en BE-50…82. |
| WP-BE-92 | Llamar al mozo; retiro por mostrador solo si se mantiene | Condicionado a D-138 y D-025; llamada adelantable tras BE-60 según roadmap. Confirmar qué alcance sigue seleccionado. |

## Primera tarea habilitada

**Siguiente:** resolver/implementar el almacenamiento contractual de llamados al mozo para completar el tablero WP-BE-51-T01; luego terminar recibo/reportes de propinas según sus fichas. WP-BE-52 sigue bloqueada por Q-27.

## Documentos fuente

- Reglas generales y prioridad: [`AGENTS.md`](../../AGENTS.md).
- Plan/estado: [`advances.md`](advances.md), [`roadmap-fases.md`](../08-agentes/roadmap-fases.md).
- Decisiones/Q: [`decisiones.md`](decisiones.md).
- Contrato vigente: [`openapi.yaml`](../04-api/openapi.yaml), [`CHANGELOG.md`](../04-api/CHANGELOG.md).
- Dominio: [`estados-y-transiciones.md`](../02-dominio/estados-y-transiciones.md), [`reglas-negocio.md`](../02-dominio/reglas-negocio.md), [`modelo-datos.md`](../02-dominio/modelo-datos.md), [`catálogo de errores`](../02-dominio/catalogo-errores.md).
- Arquitectura y validación: [`backend-arquitectura.md`](../03-arquitectura/backend-arquitectura.md), [`estrategia de pruebas`](../06-pruebas/estrategia.md), [`guía de trabajo`](../08-agentes/backend-work/README.md).

## Continuidad

Cada ficha ordena registrar evidencia, pruebas ejecutadas (comando + resultado), bloqueos/decisiones, archivos y siguiente tarea en `advances.md`. Marcar como cerrado solo con sus criterios de aceptación verificados; no crear commits ni push sin pedido explícito.
