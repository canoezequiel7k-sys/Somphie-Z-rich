# Índice de trabajo del backend

**Estado:** ejecución activa; rama `feat/backend-preparation`, HEAD actual `14b26a5`; worktree con documentación local sin commit. HEAD avanzó durante la validación; esta actualización no hizo commit ni push. **Revisión:** 2026-10-14.

### Actualización D-160 (estado vigente)

| Bloque | Estado ahora |
|---|---|
| Q-26/27/31/32/33/34 y Fase 9 | Alcance aprobado por el dueño; ver [decisiones](./decisiones.md). Fórmulas Q-26 y contratos de identidad Fase 9 no se inventan. |
| WP-ARQ-60 | ADR-0006 aprobada: PostgreSQL outbox + SSE. |
| WP-BE-60 | Parcial: V15/V17, endpoint SSE, cursor, retención, reautorización y productores de pedidos (incluida cancelación), Billing, catálogo, preparación y ciclo de vida de sesiones. Integración HTTP prueba cierre del stream al revocar un participante; faltan heartbeat, reconexión HTTP y carga de 50 streams. |
| WP-BE-52 | Parcial: V16, disponibilidad de producto/opción, ETag persistente, auditoría y `catalog.updated` atómicos. |
| WP-BE-51-T01 | Parcial: Q-31 aplicada; llamados y cambios de sesión/mesa producen eventos, pero falta validar el tablero conectado al ciclo de vida SSE. |
| WP-BE-71-T01 | Completada localmente: cierres y Q-34; historial expone creador/participante. Suite global posterior al cambio pasó. |
| WP-BE-72 / WP-BE-80/81/82 / WP-BE-90/91 | Alcances aprobados; implementación y contratos específicos pendientes. Faltan fórmulas respaldadas para algunas métricas e identidad/verificación de cliente conforme a reglas vigentes. |
| Validación (2026-10-14) | La prueba HTTP SSE focalizada y `.\backend\gradlew.bat -p backend check jacocoTestReport --no-daemon` pasaron: 380 pruebas, 0 fallos/errores/omitidas, 93,82 % (5538/5903 líneas). Markdownlint (3 documentos) y `git diff --check` pasaron. OpenAPI 0.4.1 sin cambios. |

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
| 3 | **WP-BE-51 — T01 parcial; T02 completada** | Tablero y detalle implementados; llamados persisten en V14, con autorización tenant, ciclo de vida, expiración y eventos durables. Q-31 aplicada: saldo negativo conserva `OCCUPIED` y se muestra firmado. | [T01–T02](../08-agentes/backend-work/wp-be-51-52.md); el puerto de saldo de WP-BE-70 ya está validado. | Falta validar el recorrido de tablero a través de SSE. |
| 4 | **WP-BE-52 — Parcial** | V16 persiste `catalogVersion`; los PATCH de disponibilidad de producto/opción, ETag, auditoría y `catalog.updated` están implementados con aislamiento. | [T01](../08-agentes/backend-work/wp-be-51-52.md); WP-BE-30 y D-160. | Completar cobertura de cambios administrativos restantes de versión/ETag. |
| 5 | **WP-BE-60 — Parcial** | V15/V17, outbox, stream, replay, retención y reautorización implementados; pedidos, preparación y ciclo de vida de Floor escriben atómicamente. | [T01](../08-agentes/backend-work/wp-be-60.md); WP-ARQ-60 aprobada y WP-BE-50. | Faltan pruebas de heartbeat, reconexión, revocación y 50 streams; no hay flujo persistido de expiración de pedidos. |
| 6 | **WP-BE-70 — T01/T02/T03 completadas y validadas en la rama** | PostgreSQL valida cuenta completa, reembolsos, anulaciones e interacción de propinas. `SessionBillPort` entrega el saldo derivado a floor; ADR-0010 referencia fuentes aprobadas. | [T01–T03](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-50 y WP-BE-73-T02. | WP-BE-51-T01 depende de validar eventos SSE end-to-end. |
| 7 | **WP-BE-71 — T01 completada localmente** | Cierres y pedido staff con participante opcional; actor deriva del JWT; V18 preserva filas históricas y ambas atribuciones aparecen en lecturas. PostgreSQL integra no-participante, participante, replay, permiso, tenant y referencia de sesión; suite global pasó. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y D-160. | Sin commit; validación final del backend depende de paquetes pendientes. |
| 8 | **WP-BE-72 — Renderer pendiente** | OpenAPI define PDF para sesión cerrada y RN-BIL-09/D-136 fijan contenido/leyenda; D-160 aprobó generación efímera bajo demanda. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-71 y WP-UX-70. | Evaluar biblioteca JVM/licencia e implementar generación y pruebas. |
| 9 | **WP-BE-73 — T01 parcial; T02 completada y validada en la rama** | PostgreSQL valida propinas online/efectivo, tope y configuración, propina sola, atomicidad, replay y saldo; T02 cubre permiso/tenant/concurrencia de ajustes. Suite: 357 tests, 0 fallos; JaCoCo 93,96 % (4544/4836 líneas). | [T01–T02](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y WP-ARQ-70. | T01 conserva comprobante (Q-32) y reportes (Q-26; WP-BE-72/81). |
| 10 | **WP-BE-80 — Parcial** | Hay lectura de catálogo/media y dominio floor; falta CRUD administrativo. D-160 aprobó el alcance de lectura, pero el contrato de administración sigue sin definir rutas/esquemas. | [T01–T03](../08-agentes/backend-work/wp-be-80-82.md); WP-ARQ-80 y D-160. | Definir contrato OpenAPI y `CHANGELOG.md` antes de implementar rutas nuevas. |
| 11 | **WP-BE-81 — Contrato y fórmulas pendientes** | Roadmap/UX enumeran informes; RN-TIP-08 fija propinas separadas y sin atribución laboral. Faltan operaciones, filtros, permisos y fórmulas respaldadas; exportación PDF depende del renderer. | [T01–T02](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-71 y D-160. | Excluir las métricas sin fórmula validada; completar primero contrato de lectura. |
| 12 | **WP-BE-82 — Parcial (catálogo base)** | Opciones/traducciones se leen en catálogo, pero falta API administrativa; D-160 habilita seguir definiendo el contrato. | [T01](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-80. | Contratar CRUD y probar integridad de asignaciones antes de integrar. |
| — | **Validación final — Pendiente** | Ninguna validación final acumulada para el conjunto 40–82. La validación de WP-BE-40/50 es específica de esos paquetes. | [T01](../08-agentes/backend-work/final-validation.md); todos los paquetes requeridos cerrados. | Habilita declarar backend central listo; Fase 9 no se presume obligatoria. |

Los estados reflejan la presencia y brechas observadas, no una aprobación de DoD. No se localizaron especificaciones WP-BE independientes previas a este plan; el alcance funcional sigue en el roadmap, OpenAPI y documentos de dominio. El plan creó **23 fichas**: 22 tareas de trabajo y una puerta de validación.

## Orden, dependencias y paralelización

| Trabajo | Dependencias/bloqueos | Paralelo seguro | Conflicto y límite |
|---|---|---|---|
| WP-BE-50 T01 → T02 → T03 → T04 → T05 | T01/Q-24 y T05 cerradas; paquete validado con contrato 0.3.0 y pruebas integradoras. | **No** entre T02–T04: comparten controladores/modelos/respuestas. | Un agente por vez sobre `OrdersController`, `StaffOrdersController` y OpenAPI. |
| WP-BE-51, WP-BE-52 | 51: BE-50 + BE-40 + saldo de BE-70 validados; tablero y llamados persisten. Q-31 aplicada; faltan productores vecinos y validación con SSE. 52: BE-30 y D-160 permiten disponibilidad/versionado; resta cobertura administrativa de ETag/versiones. | WP-BE-52 es independiente de BE-50; WP-BE-51 comparte productores y stream con WP-BE-60. | `SessionBillPort` provee el resumen; no duplicar saldo. |
| WP-BE-60 | WP-ARQ-60 aprobada; BE-50 completada. Outbox, endpoint, cursor, retención y productores principales ya existen. | Conectar productores Orders que falten, manteniendo transacción y alcance. | Probar desconexión, revalidación, carga/recursos y recuperación con cursor. |
| WP-BE-70 → 71 → 72; BE-73 | BE-70 y WP-BE-71-T01 validadas; D-160 aprobó Q-34 y recibo efímero. Falta renderer PDF. | Contenido de PDF fijado en RN-BIL-09/D-136; evaluar renderer. BE-73-T01 espera recibo y reporting. | No incluir semántica financiera distinta del dominio aprobado. |
| WP-BE-80 → 82; WP-BE-81 | D-160 aprobó lectura admin/reportes; BE-81 requiere BE-71; export PDF requiere el renderer; BE-82 requiere BE-80. | Definir contrato OpenAPI+changelog y completar lecturas con fórmulas respaldadas. | Excluir métricas sin fórmula y fuente aprobadas. |
| Validación final | Paquetes backend seleccionados (incluida Fase 9 por D-160) cerrados y contratos/documentos sincronizados. | No; es una puerta integradora. | No esconder fallas por cambios de umbral/exclusiones. |

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
