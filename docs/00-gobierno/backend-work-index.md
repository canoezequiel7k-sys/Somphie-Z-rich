# Índice de trabajo del backend

**Estado:** ejecución activa; rama `feat/backend-preparation`, HEAD `54ed68f` (sin commits en esta continuación; incluye cambios locales preexistentes de WP-BE-92). **Revisión:** 2026-10-17.

### Actualización D-160 (estado vigente)

| Bloque | Estado ahora |
|---|---|
| CU-02 — apertura QR idempotente | Implementada localmente: UUID v4, replay del mismo token sin duplicar efectos y token cifrado en el cuerpo persistido de idempotencia; los tests unitarios pasan. Verificación HTTP/PostgreSQL y suite global pendientes porque Docker no está disponible. |
| Q-26/27/31/32/33/34 y selección Fase 9 | Alcance aprobado por el dueño; ver [decisiones](./decisiones.md). Q-26 sigue abierta para operaciones/contratos admin y fórmulas respaldadas; Q-35 define el proceso/contrato de identidad cliente y Q-36 el envío/contrato de correo y encuesta. Q-07 resuelve retención, no estos flujos. |
| WP-ARQ-60 | ADR-0006 aprobada: PostgreSQL outbox + SSE. |
| Retenciones | Implementadas: idempotencia (24 h), nombres de invitados (12 meses) y llamados (30 días). Pruebas unitarias/PostgreSQL; expiración en competencia con acción de personal conserva estado/outbox. Suite global del 2026-10-16 pasó con 403 pruebas y 94,70 % de líneas. |
| WP-BE-60 | Parcial: además de filtros/lista/cola y OWNER global, `undo` de preparación valida estación; la regresión HTTP/PostgreSQL confirma rechazo sin mutación para estación ajena. Perfil/capacidad sostenida pendiente; desconexión TCP no reproducida de forma fiable. |
| WP-BE-52 | Completada para el contrato vigente: mutaciones de disponibilidad, versión/ETag y comparación `If-None-Match` (lista, comparación débil y `*`) cubiertos; no hay otras mutaciones administrativas de catálogo en OpenAPI. |
| WP-BE-51-T01 | Completada localmente: Q-31 aplicada; PostgreSQL cubre tablero/saldo firmado y HTTP SSE valida llamados, actualizaciones y refresco REST del tablero. |
| WP-BE-71-T01 | Completada localmente: cierres y Q-34; historial expone creador/participante. Suite global posterior al cambio pasó. |
| WP-BE-72 | Validada localmente: PDFBox 3.0.8 (Apache-2.0/JVM 25), bajo demanda, guest/cerrado/24 h, snapshots y Billing compartido; leyenda D-136, saldos, propina de pagos vigentes y nombres largos cubiertos por HTTP/PostgreSQL. Sin persistencia del PDF. |
| WP-BE-80/82 | Parcial: WP-BE-80-T01 cubre lectura local (límite 1 MB, firmas WebP/JPEG/PNG, contención de rutas), V19 exige alt y una prueba HTTP integra ruta pública, filesystem, tipo/cache y 404. Q-26 debe fijar operaciones, permisos, campos, errores y reglas para catálogo/imágenes, QR, usuarios/settings y grupos/opciones/traducciones; OpenAPI solo define `GET /media/{path}`. |
| WP-BE-81 | Bloqueada por Q-26: faltan contrato/filtros y fórmulas respaldadas para informes; exportación/auditoría también necesita precisar su contrato y permisos. No inferir métricas desde las pantallas. |
| WP-BE-90/91 | Bloqueadas por Q-35/Q-36: falta proceso/contrato de autenticación y vinculación de clientes, y política/contrato de correo, destinatarios, feedback y reintentos. D-160 selecciona Fase 9, pero no decide estos detalles. |
| Validación (2026-10-16) | `.\backend\gradlew.bat -p backend check jacocoTestReport --no-daemon`: 402 pruebas, 0 fallos/errores, 1 omitida por symlink no disponible en Windows; 5951/6284 líneas (94,70 %). Markdownlint focal y `git diff --check` pasaron; el lint global conserva 2 problemas preexistentes. OpenAPI no cambió en esta continuación. |

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
| 3 | **WP-BE-51 — T01/T02 completadas localmente** | Tablero/detalle y llamados persistidos; autorización tenant, ciclo de vida, expiración y eventos durables. PostgreSQL y HTTP SSE validan saldo firmado, estados y refresco del tablero. | [T01–T02](../08-agentes/backend-work/wp-be-51-52.md); el puerto de saldo de WP-BE-70 ya está validado. | Ninguno dentro del alcance de T01/T02; WP-BE-60 conserva revisión de productores/límites. |
| 4 | **WP-BE-52 — Completada en alcance contratado (2026-10-14)** | V16 persiste `catalogVersion`; PATCH de disponibilidad de producto/opción, ETag y `If-None-Match` para tags débiles/listas/wildcard; prueba HTTP asegura que un validador anterior no oculte una versión nueva. | [T01](../08-agentes/backend-work/wp-be-51-52.md); WP-BE-30 y D-160. | CRUD adicional requiere contrato aprobado; OpenAPI no define otras mutaciones. |
| 5 | **WP-BE-60 — Parcial** | Outbox, replay y revocación cubiertos; lista/detalle/replay aplican alcance por roles combinados y mantienen Cocina sola limitada a sus estaciones. Pruebas integradas pasaron en suite global (2026-10-16). | [T01](../08-agentes/backend-work/wp-be-60.md); WP-ARQ-60 aprobada y WP-BE-50. | Acordar capacidad sostenida y reproducir desconexión TCP normal. |
| 6 | **WP-BE-70 — T01/T02/T03 completadas y validadas en la rama** | PostgreSQL valida cuenta completa, reembolsos, anulaciones e interacción de propinas. `SessionBillPort` entrega el saldo derivado a floor; ADR-0010 referencia fuentes aprobadas. | [T01–T03](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-50 y WP-BE-73-T02. | WP-BE-51-T01/T02 ya validadas end-to-end. |
| 7 | **WP-BE-71 — T01 completada localmente** | Cierres y pedido staff con participante opcional; actor deriva del JWT; V18 preserva filas históricas y ambas atribuciones aparecen en lecturas. PostgreSQL integra no-participante, participante, replay, permiso, tenant y referencia de sesión; suite global pasó. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y D-160. | Sin commit; validación final del backend depende de paquetes pendientes. |
| 8 | **WP-BE-72 — Implementada localmente** | Contrato existente cubierto con PDFBox: contenido desde snapshots/cuenta, permisos, cierre y TTL; pruebas HTTP PostgreSQL verifican el PDF generado. | [T01](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-71 y WP-UX-70. | Sin cambios de OpenAPI. |
| 9 | **WP-BE-73 — T01 parcial; T02 completada y validada en la rama** | PostgreSQL valida propinas online/efectivo, tope y configuración, propina sola, atomicidad, replay y saldo; T02 cubre permiso/tenant/concurrencia de ajustes. Recibo implementado localmente. | [T01–T02](../08-agentes/backend-work/wp-be-70-73.md); WP-BE-70 y WP-ARQ-70. | Reporting pendiente de contrato y fórmulas (Q-26; WP-BE-81). |
| 10 | **WP-BE-80 — Parcial** | El adaptador local limita a 1 MB, valida firmas y contiene symlinks; V19 exige alt y prueba HTTP integra lectura pública, tipo/cache y 404. Falta procesamiento/upload y CRUD; OpenAPI solo define `GET /media/{path}`. | [T01–T03](../08-agentes/backend-work/wp-be-80-82.md); WP-ARQ-80 y D-160. | Definir contrato OpenAPI y `CHANGELOG.md` antes de implementar rutas nuevas. |
| 11 | **WP-BE-81 — Contrato y fórmulas pendientes** | Roadmap/UX enumeran informes; RN-TIP-08 fija propinas separadas y sin atribución laboral. Faltan operaciones, filtros, permisos y fórmulas respaldadas; renderer PDF ya está disponible. | [T01–T02](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-71 y D-160. | Excluir las métricas sin fórmula validada; completar primero contrato de lectura. |
| 12 | **WP-BE-82 — Parcial (catálogo base)** | Opciones/traducciones se leen en catálogo, pero falta API administrativa; D-160 habilita seguir definiendo el contrato. | [T01](../08-agentes/backend-work/wp-be-80-82.md); WP-BE-80. | Contratar CRUD y probar integridad de asignaciones antes de integrar. |
| — | **Validación final — Pendiente** | Ninguna validación final acumulada para el conjunto 40–82. La validación de WP-BE-40/50 es específica de esos paquetes. | [T01](../08-agentes/backend-work/final-validation.md); todos los paquetes requeridos cerrados. | Habilita declarar backend central listo; Fase 9 no se presume obligatoria. |

Los estados reflejan la presencia y brechas observadas, no una aprobación de DoD. No se localizaron especificaciones WP-BE independientes previas a este plan; el alcance funcional sigue en el roadmap, OpenAPI y documentos de dominio. El plan creó **23 fichas**: 22 tareas de trabajo y una puerta de validación.

## Orden, dependencias y paralelización

| Trabajo | Dependencias/bloqueos | Paralelo seguro | Conflicto y límite |
|---|---|---|---|
| WP-BE-50 T01 → T02 → T03 → T04 → T05 | T01/Q-24 y T05 cerradas; paquete validado con contrato 0.3.0 y pruebas integradoras. | **No** entre T02–T04: comparten controladores/modelos/respuestas. | Un agente por vez sobre `OrdersController`, `StaffOrdersController` y OpenAPI. |
| WP-BE-51, WP-BE-52 | 51: BE-50 + BE-40 + saldo de BE-70 validados; tablero y llamados persisten. Q-31 aplicada; faltan productores vecinos y validación con SSE. 52: BE-30 y D-160 permiten disponibilidad/versionado; resta cobertura administrativa de ETag/versiones. | WP-BE-52 es independiente de BE-50; WP-BE-51 comparte productores y stream con WP-BE-60. | `SessionBillPort` provee el resumen; no duplicar saldo. |
| WP-BE-60 | WP-ARQ-60 aprobada; BE-50 completada. Outbox, endpoint, cursor, retención, productores principales y pruebas HTTP de ciclo de vida ya existen. | Conectar productores Orders que falten, manteniendo transacción y alcance. | Medir carga/recursos y recuperación con cursor; no declarar cierre sin 50 streams. |
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
| WP-BE-90 | Registro/verificación/login de cliente, vincular participante y email | Fase 9 seleccionada por D-160; Q-35 precisa reglas/contrato de identidad y vinculación; Q-36 define el transporte de verificación sin inventar proveedor. |
| WP-BE-91 | Email de comprobante y encuesta | Seleccionado dentro de Fase 9; depende de BE-90 y Q-36 (destinatarios, disparadores, feedback, transporte/configuración, deduplicación y reintentos). |
| WP-BE-92 | Llamar al mozo; retiro por mostrador solo si se mantiene | Llamados y productores `waiter.*` implementados; expiración desde OPEN/ACKNOWLEDGED persiste y emite evento incluso si acknowledge/resolve responde STATE_CONFLICT. Retiro por mostrador sigue opcional según D-025. |

## Siguiente paso

La secuencia histórica de abajo quedó superada. WP-BE-60 conserva solo la definición/medición del perfil de capacidad y la reproducción de FIN/reset TCP; WP-BE-80/81/82 requieren resolver Q-26, y BE-90/91 requieren Q-35/Q-36 y los contratos asociados antes de implementar sus superficies.

## Documentos fuente

- Reglas generales y prioridad: [`AGENTS.md`](../../AGENTS.md).
- Plan/estado: [`advances.md`](advances.md), [`roadmap-fases.md`](../08-agentes/roadmap-fases.md).
- Decisiones/Q: [`decisiones.md`](decisiones.md).
- Contrato vigente: [`openapi.yaml`](../04-api/openapi.yaml), [`CHANGELOG.md`](../04-api/CHANGELOG.md).
- Dominio: [`estados-y-transiciones.md`](../02-dominio/estados-y-transiciones.md), [`reglas-negocio.md`](../02-dominio/reglas-negocio.md), [`modelo-datos.md`](../02-dominio/modelo-datos.md), [`catálogo de errores`](../02-dominio/catalogo-errores.md).
- Arquitectura y validación: [`backend-arquitectura.md`](../03-arquitectura/backend-arquitectura.md), [`estrategia de pruebas`](../06-pruebas/estrategia.md), [`guía de trabajo`](../08-agentes/backend-work/README.md).

## Continuidad

Cada ficha ordena registrar evidencia, pruebas ejecutadas (comando + resultado), bloqueos/decisiones, archivos y siguiente tarea en `advances.md`. Marcar como cerrado solo con sus criterios de aceptación verificados; no crear commits ni push sin pedido explícito.
