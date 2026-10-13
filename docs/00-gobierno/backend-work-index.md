# Índice de trabajo del backend

**Estado:** plan de ejecución; no declara cerrado ningún paquete. **Base inspeccionada:** rama `feat/backend-preparation`, HEAD `c317da0` antes de estos cambios documentales; worktree limpio al iniciar. El código de WP-BE-50 está respaldado por `1ed84fd`; continuidad anterior, por `c317da0`.

## Cómo usar este plan

1. Elegí una ficha habilitada en la tabla y empezá por ella; no leas todas las fichas.
2. Seguí el prompt y la entrega de [`README.md`](../08-agentes/backend-work/README.md).
3. La ficha enlaza solo las fuentes necesarias. Las decisiones y el contrato oficiales prevalecen.
4. Los estados son una lectura documental y de código a la fecha indicada; antes de ejecutar, verificá que no haya cambiado Git ni se haya cerrado la tarea.

## Mapa de paquetes

| Orden | Paquete / estado observado | Evidencia y bloqueo | Tareas / dependencia oficial | Frontend y cierre |
|---|---|---|---|---|
| 1 | **WP-BE-50 — T05 bloqueada en validación** | Q-24 resuelta; OpenAPI 0.3.0 define FIFO/SLA por `acceptedAt`, campos históricos nullable y keyset eventual. Lints y tests unitarios pasan; Testcontainers no inicia sin Docker (2 suites afectadas), por lo que falta integración y JaCoCo. | [T01–T05](../08-agentes/backend-work/wp-be-50.md); WP-BE-44. T05 exige reintentar `check jacocoTestReport` con Docker operativo. | No habilita WP-BE-51 hasta cerrar T05 y validar WP-BE-40. |
| 2 | **WP-BE-51 — No iniciado / dependencias sin validar** | No hay handlers actuales. WP-BE-40 aparece ✅ en bitácora, pero su alcance exige expiración y rate-limit; el código inspeccionado solo contiene la regla de dominio de expiración y no se encontró job ni limitador. | [T01–T02](../08-agentes/backend-work/wp-be-51-52.md); WP-BE-50 + validación de WP-BE-40; `balanceMinor` requiere fuente de billing WP-BE-70-T01 (dependencia técnica inferida del schema). | No iniciar hasta cerrar WP-BE-50 y validar WP-BE-40; luego contrato/aislamiento. |
| 3 | **WP-BE-52 — Bloqueado por Q-27** | No hay handlers `PATCH`; `catalogVersion` es constante y no existe `event_log`/publisher para el evento contractual. | [T01](../08-agentes/backend-work/wp-be-51-52.md); WP-BE-30 + respuesta Q-27. | No tocar disponibilidad parcialmente; implementar versión, auditoría y evento de manera coherente. |
| 4 | **WP-BE-60 — No iniciado / decisión previa** | No se encontraron módulo `notifications`, `event_log` ni endpoint SSE. ADR-0006 sigue en propuesta. | [T01](../08-agentes/backend-work/wp-be-60.md); WP-ARQ-60 y WP-BE-50. | Los clientes pueden simular eventos; integración real después de SSE y reconexión probados. |
| 5 | **WP-BE-70 — Parcial (dominio)** | Existen reglas/modelos y tests de `billing`; no API, aplicación ni persistencia de billing. | [T01–T03](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-50 y WP-ARQ-70/ADR de pagos. | Mock de cuenta/pago; integrar tras probar balance, idempotencia, lock y proveedor simulado. |
| 6 | **WP-BE-71 — No iniciado** | No hay endpoints de cierre ni de pedido en nombre del cliente en backend. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70. | Mock permitido; integración real después de transacciones/estados auditados. |
| 7 | **WP-BE-72 — No iniciado** | El contrato tiene `/sessions/current/receipt.pdf`; no hay `PdfRenderer`/adaptador backend. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-71 y WP-UX-70; definir ADR de PDF antes del adaptador. | El cliente puede mockear descarga; conectar cuando PDF contractual esté probado. |
| 8 | **WP-BE-73 — Parcial (dominio)** | Reglas de ajustes/cortesía y propina tienen modelos/tests; falta persistencia, casos de uso y API. | [T01–T02](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y WP-ARQ-70. | Mock ahora; integrar tras probar límites/roles, anulación e interacción con cuenta. |
| 9 | **WP-BE-80 — Parcial** | Hay lectura de catálogo/media y dominio floor; falta CRUD administrativo. La API no define rutas CRUD admin. Q-26 bloquea implementación contractual. | [T01–T03](../08-agentes/backend-work/wp-be-80-82.md); WP-ARQ-80 y resolución de Q-26. | UI mockeable; no integrar admin hasta aprobación del contrato. |
| 10 | **WP-BE-81 — No iniciado** | No se encontró módulo `reporting` ni endpoints de informes/auditoría; Q-26 incluye este hueco de contrato. | [T01–T02](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-71 y contrato aprobado. | Mock de informes; integración después de contratos, queries y aislamiento verificados. |
| 11 | **WP-BE-82 — Parcial (catálogo base)** | Opciones/traducciones se leen en catálogo, pero no hay API administrativa; Q-26. | [T01](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-80 y contrato aprobado. | Mockeable; integrar tras contrato CRUD y pruebas de consistencia de asignaciones. |
| — | **Validación final — Pendiente** | Ninguna validación final acumulada para el conjunto 50–82. | [T01](../08-agentes/backend-work/final-validation.md); todos los paquetes requeridos cerrados. | Habilita declarar backend central listo; Fase 9 no se presume obligatoria. |

Los estados reflejan la presencia y brechas observadas, no una aprobación de DoD. No se localizaron especificaciones WP-BE independientes previas a este plan; el alcance funcional sigue en el roadmap, OpenAPI y documentos de dominio. El plan creó **23 fichas**: 22 tareas de trabajo y una puerta de validación.

## Orden, dependencias y paralelización

| Trabajo | Dependencias/bloqueos | Paralelo seguro | Conflicto y límite |
|---|---|---|---|
| WP-BE-50 T01 → T02 → T03 → T04 → T05 | T01/Q-24 cerrada; T05 valida los cambios de cola y el contrato 0.3.0 junto con las pruebas integradoras. | **No** entre T02–T04: comparten controladores/modelos/respuestas. | Un agente por vez sobre `OrdersController`, `StaffOrdersController` y OpenAPI. |
| WP-BE-51, WP-BE-52 | 51: BE-50 + BE-40 validado (expiración/rate-limit no verificados) + fuente de `balanceMinor`. 52: BE-30 + resolución Q-27 sobre versionado/evento. | 52 es independiente de BE-50, pero bloqueada por Q-27; 51 solo después de BE-50 y la validación BE-40. | 51 no cambia hasta liberarse sus dependencias; 52 limita cambios a catálogo/availability y requiere acordar event_log. |
| WP-BE-60 | WP-ARQ-60 acepta o reemplaza ADR-0006; BE-50 como define roadmap. | El spike/decisión ARQ precede; no implementar SSE en paralelo con decisión. | Una sola persona dueña de eventos y persistencia `event_log`. |
| WP-BE-70 → 71 → 72; BE-73 | BE-70 depende de BE-50 y definición ARQ-70; BE-71 depende 70; 72 depende 71 + UX-70 + decisión de PDF; 73 depende 70. | 73 solo puede avanzar separada después de fijar puertos/modelos de 70; hasta entonces, **secuencial**. | Locks, tablas y migraciones de billing son compartidos. No paralelizar migraciones sin coordinación. |
| WP-BE-80 → 82; WP-BE-81 | Contratos admin/reportes pendientes (Q-26); BE-81 requiere BE-71; BE-82 requiere BE-80. | Sin Q-26, solo investigación documental. Después, grupos distintos pueden diseñarse en paralelo con responsables de rutas separados. | Ordenar migraciones/versiones y reservar rutas/esquemas antes de ejecución concurrente. |
| Validación final | BE-50…82 requeridos cerrados y contratos/documentos sincronizados. | No; es una puerta integradora. | No esconder fallas por cambios de umbral/exclusiones. |

Dependencias de fase y entregables se citan en [`roadmap-fases.md`](../08-agentes/roadmap-fases.md); no se modificó el roadmap. La dependencia BE-51 → BE-70-T01 por `balanceMinor` es inferencia técnica por el schema, identificada como tal.

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

**Siguiente: WP-BE-50-T05** para validar las tareas T02–T04 ya implementadas. Q-24 aún bloquea T05 y cualquier inicio de WP-BE-51: formalizar los detalles contractuales de la cola, implementarlos y completar las pruebas mixtas/estados/SLA antes de cerrar WP-BE-50.

## Documentos fuente

- Reglas generales y prioridad: [`AGENTS.md`](../../AGENTS.md).
- Plan/estado: [`advances.md`](advances.md), [`roadmap-fases.md`](../08-agentes/roadmap-fases.md).
- Decisiones/Q: [`decisiones.md`](decisiones.md).
- Contrato vigente: [`openapi.yaml`](../04-api/openapi.yaml), [`CHANGELOG.md`](../04-api/CHANGELOG.md).
- Dominio: [`estados-y-transiciones.md`](../02-dominio/estados-y-transiciones.md), [`reglas-negocio.md`](../02-dominio/reglas-negocio.md), [`modelo-datos.md`](../02-dominio/modelo-datos.md), [`catálogo de errores`](../02-dominio/catalogo-errores.md).
- Arquitectura y validación: [`backend-arquitectura.md`](../03-arquitectura/backend-arquitectura.md), [`estrategia de pruebas`](../06-pruebas/estrategia.md), [`guía de trabajo`](../08-agentes/backend-work/README.md).

## Continuidad

Cada ficha ordena registrar evidencia, pruebas ejecutadas (comando + resultado), bloqueos/decisiones, archivos y siguiente tarea en `advances.md`. Marcar como cerrado solo con sus criterios de aceptación verificados; no crear commits ni push sin pedido explícito.
