# Registro de decisiones

Leyenda: ✅ Confirmada (definida por ti en el plan maestro) · 🟢 Aceptada en chat · 🟡 Propuesta (**requiere tu validación**) · 🔴 Pendiente.

## 1. Decisiones confirmadas (plan maestro)

| ID | Decisión | Estado |
|---|---|---|
| D-001 | Proyecto independiente de portfolio (no atado al TFI) | ✅ |
| D-002 | Planificar el alcance completo desde el inicio; implementar por fases pequeñas | ✅ |
| D-003 | Una sola cafetería, **diseñada para ampliarse** (todas las tablas llevan `restaurant_id`) | ✅ |
| D-004 | Entrega demostrable (demo pública + README + video/capturas) | ✅ |
| D-005 | Un empleado autorizado decide aceptar o rechazar cada pedido | ✅ |
| D-006 | Pago simulado; nunca dinero real ni datos de tarjeta | ✅ |
| D-007 | Cuenta compartida o dividida entre participantes (pagos parciales por monto) | ✅ |
| D-008 | Se puede pedir como invitado; una cuenta registrada habilita extras | ✅ |
| D-009 | Autenticación por email | ✅ |
| D-010 | Extras del TFI: solo los que aporten valor al portfolio | ✅ |
| D-011 | Tiempo real, cuando el flujo base REST funcione | ✅ |
| D-012 | Email y PDF incluidos en el alcance | ✅ |
| D-013 | Diseño de nivel premium | ✅ |
| D-014 | IA como pareja de programación; se prioriza aprender el porqué | ✅ |
| D-015 | Stack: Kotlin + Compose, React + TS, Spring Boot + Kotlin, PostgreSQL | ✅ |
| D-016 | Despliegue en nube temprano con presupuesto cero | ✅ |
| D-017 | Documentación desde el primer día | ✅ |
| D-018 | Entorno Windows + Git | ✅ |
| D-019 | Clean Architecture sin sobreingeniería | ✅ |
| D-020 | Nombre del producto: **Somphie Zúrich** | 🟢 |

## 2. Decisiones aceptadas en chat

| ID | Decisión | Estado |
|---|---|---|
| D-021 | Autorización por **permisos**; los roles son paquetes de permisos. MVP: Cliente (invitado/registrado), Personal (Mozo, Barra, Cocina), Dueño. Maître y Supervisor se agregan después sin migrar el modelo | 🟢 |
| D-022 | El QR contiene solo un identificador opaco de mesa. El primer escaneo abre la sesión y entrega un token de invitado acotado; quien se suma después usa un código corto | 🟢 |
| D-023 | Cuenta compartida, pagos parciales por monto, sin sobrepago. Cierra el personal con saldo 0 y sin pedidos en curso. El dueño puede forzar el cierre (auditado) | 🟢 |
| D-024 | Rechazo con motivo opcional, conservado en historial; el cliente corrige y reenvía | 🟢 |
| D-025 | MVP: servicio en mesa. Retiro por mostrador en Fase 9. Delivery fuera de alcance | 🟢 |
| D-026 | Primero REST (+polling); luego se evalúa **SSE antes que STOMP** (ver ADR-0006) | 🟢 |
| D-027 | Un único proyecto React; el panel vive en `/admin`, con carga diferida | 🟢 |
| D-028 | Fase 0 (especificación) completa antes de escribir código | 🟢 |

## 3. Propuestas de la v0.1 — resultado de tu validación

Todas quedaron 🟢 **aceptadas** (D-101 a D-128), salvo **D-121**, que no traía marca y quedó 🟡. Se confirmó después (ver D-154), por lo que ahora está 🟢.

| ID | Decisión | Estado |
|---|---|---|
| D-101 | Dinero en enteros de centavos, moneda única ARS | 🟢 |
| D-102 | IDs UUID + código visible `#NNN` por día de negocio | 🟢 |
| D-103 | Código/API/DB/commits en inglés; docs y UI en es-AR (la UI cliente suma `en`, ver D-140) | 🟢 |
| D-104 | Contract-first con `openapi.yaml` a mano | 🟢 |
| D-105 | Token de invitado opaco; cuentas con JWT corto + refresh rotativo | 🟢 |
| D-106 | Límites operativos configurables en `restaurant_settings` | 🟢 |
| D-107 | `PRICE_CHANGED` con total reconocido | 🟢 |
| D-108 | Pedido pendiente expira a los 15 min | 🟢 |
| D-109 | Sesión vacía expira a los 30 min | 🟢 |
| D-110 | Aceptar con exclusiones | 🟢 |
| D-111 | Cancelar ítems con motivo + reembolso simulado | 🟢 |
| D-112 | Pagos en efectivo registrados por el personal | 🟢 |
| D-113 | Pedido a nombre del cliente (auto-aceptado) | 🟢 |
| D-114 | Abrir sesión desde el personal | 🟢 |
| D-115 | Aceptan/rechazan: Mozo, Barra, Dueño | 🟢 |
| D-116 | Disponibilidad: Mozo, Barra, Cocina, Dueño (ahora también opciones) | 🟢 |
| D-117 | Token en solo lectura 24 h tras el cierre | 🟢 |
| D-118 | SSE + `event_log` + `Last-Event-ID` | 🟢 |
| D-119 | Horario de atención y `RESTAURANT_CLOSED` | 🟢 |
| D-120 | Monorepo | 🟢 |
| D-121 | Tests de contrato (`swagger-request-validator`) + mocks Prism | 🟢 (confirmada en D-154) |
| D-122 | APK por GitHub Releases | 🟢 |
| D-123 | Notas por ítem (≤140). **Opciones/modificadores pasan de "reservadas" a incluidas en MVP** (ver D-129) | 🟢 |
| D-124 | Participantes `HOST`/`MEMBER` | 🟢 |
| D-125 | Modo demo: centavos `.13` simulan rechazo | 🟢 |
| D-126 | Divisor de cuenta solo en UI | 🟢 |
| D-127 | Módulo `audit` | 🟢 |
| D-128 | `orders` dueño del agregado Pedido+Ítems | 🟢 |

## 3.1 Decisiones nuevas derivadas de tus respuestas (v0.2)

| ID | Decisión | Origen | Estado |
|---|---|---|---|
| D-129 | **Opciones/modificadores en el MVP** (tamaño, leche, extras con precio). Grupos reutilizables asignables a varios productos; snapshot histórico en el ítem; disponibilidad alternable. Entra en **Fase 3** | Q-02 | 🟢 (mecánica 🟡) |
| D-130 | **Propina libre**: monto voluntario que el participante adjunta a su pago (o paga solo propina). No reduce el saldo, no es cargo, se reporta aparte. Tope de cordura configurable para evitar errores de tipeo | Q-01 | 🟢 (tope 🟡) |
| D-131 | **Descuentos, cortesías y ajustes manuales** en el alcance (los tres tipos). Motivo obligatorio, auditoría, límite para el Mozo y sin límite para el Dueño; importe congelado al aplicarlo | Q-03 | 🟢 (mecánica 🟡) |
| D-132 | **Imágenes en carpeta**: puerto `ImageStorage` con adaptador de carpeta. En la demo, imágenes versionadas en el repo y servidas como estáticos | Q-04 | 🟢 |
| D-133 | **Web (cliente + admin) en Vercel** mientras sea gratis. El backend y PostgreSQL **no** van en Vercel (ver D-156) | Q-05 | 🟢 / 🔴 backend |
| D-134 | Marca en `design/img/` (`ic_icon.png`, etc.) | Q-06 | 🟢 |
| D-135 | Retención de datos: se adopta la propuesta de `modelo-datos.md` §5; nombres de invitados se anonimizan a los 12 meses (🟡 plazo) | Q-07 | 🟢 |
| D-136 | Comprobante: leyenda "Comprobante no fiscal — no válido como factura" + datos ficticios del local | Q-08 | 🟢 |
| D-137 | Alérgenos: lista de 14 (`GLUTEN, CRUSTACEANS, EGG, FISH, PEANUTS, SOY, MILK, TREE_NUTS, CELERY, MUSTARD, SESAME, SULPHITES, LUPIN, MOLLUSCS`); **verificar contra el Código Alimentario Argentino** antes de publicar | Q-09 | 🟢 / 🟡 verificar |
| D-138 | **Llamar al mozo** incluido (con motivo "pedir la cuenta"). Puede adelantarse a justo después de la Fase 6 | Q-10 | 🟢 |
| D-139 | Sin impresión de comandas por ahora; disponibilidad **manual** (sin stock real) | Q-11/Q-12 | 🟢 |
| D-140 | **i18n en la web cliente**: es-AR + en desde el diseño; alemán después. Panel admin y app Android solo es-AR. Catálogo con traducciones por idioma | Q-13 | 🟢 (idiomas 🟡) |
| D-141 | **Multi-sucursal simulada**: `restaurant_id` en todo y pruebas de aislamiento con dos restaurantes; sin UI multi-local | Q-14 | 🟢 |

## 3.2 Decisiones tomadas por delegación del dueño (D-142 … D-159)

El dueño delegó: *"toma las mejores decisiones posibles para que sea escalable y bueno"*. Las toma el Arquitecto y el dueño puede **revocar cualquiera**. El detalle (alternativas descartadas y qué estudiar) está en `00-gobierno/advances.md` §2. Estado 🟢 (delegada) = tomada por delegación, revocable.

| ID | Decisión | Origen | Estado |
|---|---|---|---|
| D-142 | **Descuentos del Mozo: hasta 15 %** acumulado de los cargos brutos de la sesión (suma de todos sus ajustes sin `adjustment.apply_any`). Configurable en `restaurant_settings.maxStaffDiscountPercent`; el tope absoluto `maxStaffAdjustmentMinor` es opcional. El Dueño no tiene límite. Reemplaza la propuesta de 10 % | Q-17 | 🟢 (delegada) |
| D-143 | **HOST en sesiones abiertas por el personal**: la sesión nace sin HOST y el primero en unirse lo es; los siguientes son `MEMBER`. La unicidad la garantiza un índice único parcial en la base; si dos se unen a la vez, el segundo se inserta como `MEMBER` en la misma transacción. Mientras no haya HOST, el personal conserva `participant.revoke` y rotar código | H-03 | 🟢 (delegada) |
| D-144 | **Restaurante en endpoints públicos**: con `tableCode` se toma de la mesa; en `GET /menu`, con token manda el del token y sin token se usa `restaurantId` o el predeterminado del despliegue (`DEFAULT_RESTAURANT_ID`); id desconocido → `404`. `PublicTable` suma `restaurantId` opcional | H-04 | 🟢 (delegada) |
| D-145 | Campos `proposed` del contrato (`Participant.revoked`, `Order.rejection`, campos de `StaffTable` y `StaffOpenSessionResponse`) quedan **aceptados** | H-05 | 🟢 (delegada) |
| D-146 | **Pago solo propina**: RN-BIL-03 aplica solo con `amountMinor > 0`; con `0` rige RN-TIP-01. Gana el contrato (ver D-130) | H-02 | 🟢 (delegada) |
| D-147 | `TIP_NOT_ALLOWED` se mantiene en **409** (conflicto de estado), también en el contrato | H-08 | 🟢 (delegada) |
| D-148 | **Redocly CLI** como única herramienta de lint, vista previa y empaquetado del contrato; reemplaza a Spectral | H-09 | 🟢 (delegada) |
| D-149 | **Una sola copia** de cada documento: la oficial es la de `docs/` | H-10 | 🟢 (delegada) |
| D-150 | `CHANGELOG.md` del contrato: más reciente arriba, con fecha; `0.x` = borrador, `1.0.0` = congelado al cerrar la Fase 1; `info.version` coincide con la última entrada | H-07 | 🟢 (delegada) |
| D-151 | Nuevo `02-dominio/catalogo-auditoria.md` con el vocabulario cerrado de acciones de auditoría | — | 🟢 (delegada) |
| D-152 | **Idiomas**: interfaz de la web cliente en `es-AR` y `en` en el MVP; traducciones del menú opcionales con fallback a `es-AR`; `de` después | Q-16 | 🟢 (delegada) |
| D-153 | **Propina**: campo libre + montos rápidos como ayuda de UI (el servidor solo recibe `tipMinor`); sin atribución a un empleado en el MVP | Q-18 | 🟢 (delegada) |
| D-154 | Se **confirma D-121** (tests de contrato + mocks Prism) | Q-19 | 🟢 (delegada) |
| D-155 | **Imágenes**: subir desde el admin solo en local (`MEDIA_UPLOAD_ENABLED=true`); en la demo, imágenes versionadas en el repo | Q-20 | 🟢 (delegada) |
| D-156 | **Backend como imagen Docker** configurada solo por variables de entorno, sin atarse a un proveedor. El hosting y PostgreSQL se eligen en la Fase 2 con el checklist de `nube.md` | Q-15 | 🟢 (delegada) / 🔴 proveedor |
| D-157 | **Consolidar la documentación ("v0.3")** antes de la Fase 2: integrar en el cuerpo las secciones "v0.2" apendadas | H-06 | 🟢 (delegada) |
| D-158 | **Pago mínimo con saldo chico**: el monto principal debe cumplir `min(minPaymentMinor, balance) ≤ amount ≤ balance`. Si el saldo es menor al mínimo, se paga **exactamente el saldo**. Así siempre se puede saldar la cuenta | H-13 | 🟢 (delegada) |
| D-159 | **Cierre forzado con saldo negativo**: en la misma transacción, tras cancelar ítems, si `balance < 0` el sistema registra un **reembolso simulado automático** por el exceso (no incluye propinas), auditado con el actor y la marca "automático por cierre forzado". Si `balance > 0` queda como **saldo no cobrado** en auditoría y comprobante. Los pagos y propinas ya registrados se conservan | H-14 | 🟢 (delegada) |

## 4. Preguntas

### 4.1 Resueltas
| ID | Resolución |
|---|---|
| Q-01 | Propina libre → D-130 |
| Q-02 | Sí, en MVP → D-129 |
| Q-03 | Sí (descuentos, cortesías y ajustes) → D-131 |
| Q-04 | En una carpeta → D-132 |
| Q-05 | Vercel para la web → D-133 |
| Q-06 | `design/img/` → D-134 |
| Q-07 | Sí → D-135 |
| Q-08, Q-09 | Aceptadas → D-136, D-137 |
| Q-10 | Sí → D-138 |
| Q-11, Q-12 | Posterior / manual → D-139 |
| Q-13 | Sí → D-140 |
| Q-14 | Simulada → D-141 |
| Q-15 | Backend como imagen Docker, proveedor a elegir en Fase 2 → D-156 |
| Q-16 | Interfaz `es-AR` + `en`, menú traducible de forma opcional → D-152 |
| Q-17 | Mozo hasta 15 % acumulado, configurable → D-142 |
| Q-18 | Campo libre + montos rápidos, sin atribución → D-153 |
| Q-19 | D-121 confirmada → D-154 |
| Q-20 | Subida de imágenes solo en local → D-155 |

### 4.2 Pendientes 🔴
| ID | Pregunta | Impacto | Cuándo |
|---|---|---|---|
| Q-21 | Marca: existen `ic_icon.svg` (1,54 MB) y `boceto_v.1.0.png`. Falta confirmar si el SVG es el isotipo **final**, si hay logo horizontal y cómo se generó (el tamaño es inusual: ¿imagen incrustada o vectorizado automático?). Puede requerir optimizarlo | Diseño (tokens, favicon, ícono Android) | Fase 1 |

## 5. Consultas abiertas (agentes → arquitecto)

> Un agente bloqueado agrega una fila. El arquitecto responde y, si cambia algo, actualiza docs y `CHANGELOG`.

| # | Fecha | Agente | Consulta | Estado | Respuesta |
|---|---|---|---|---|---|
| Q-22 | 2026-10-12 | BE | `Order.version` es obligatorio en OpenAPI y sirve para descartar respuestas/eventos viejos, pero `customer_order` y el dominio no lo guardan. ¿Valor inicial y cuándo incrementa? Bloquea respuestas completas de preparación. | Resuelta por el dueño (2026-10-12) | Inicia en 1; aumenta una sola vez por mutación persistida del agregado; no cambia por lectura, error o replay sin mutación. Actualización optimista atómica. |
| Q-23 | 2026-10-12 | BE | OpenAPI acepta `optionIds` al crear ítems y exige snapshots de opciones en el pedido/cola; `CreateOrderRequest` y `order_item` actualmente no los conservan. ¿Confirmar alcance de snapshot y migración compatible? | Resuelta por el dueño (2026-10-12) | Snapshot JSONB inmutable con IDs, grupo, nombres y delta; validar selección y disponibilidad al crear, calcular el precio en backend. Registros históricos sin opciones usan `[]` sin reconstrucción ficticia. |
| Q-24 | 2026-10-12 | BE → ARQ/dueño | FIFO/SLA usan `acceptedAt`; incluir históricos sin fabricar tiempos; cursor de MVP eventual; métricas agregadas pospuestas. | Resuelta por el dueño (2026-10-13) | Alinear OpenAPI a FIFO por `acceptedAt`; devolver `queuedAt: null` cuando no se guardó y `delayed: null` solo cuando falta `acceptedAt` y el SLA no es evaluable. Para históricos sin aceptación, desempatar por ID únicamente para hacer estable el orden del grupo no ordenable, sin atribuirle semántica FIFO. Clientes deben tolerar estos nulos; no se reconstruyen fechas ni se agregan métricas. |
| Q-25 | 2026-10-12 | BE | RN-CAT-02, RN-OPT-02/03/11 y el modelo documentan `active` para categorías, productos, grupos y opciones, pero las migraciones no lo persistían. | Resuelta (2026-10-13) | V11 agrega `active BOOLEAN NOT NULL DEFAULT TRUE` en las cuatro tablas: conserva los registros existentes como activos. El catálogo omite elementos archivados; crear pedidos valida producto/categoría activos y opciones/grupos activos, además de `available`. `available` sigue representando disponibilidad operativa y no reemplaza el ciclo de vida `active`. |
| Q-26 | 2026-10-13 | ARQ (consulta BE) | El roadmap de WP-BE-80/81/82 requiere administración de catálogo, mesas/QR, usuarios/roles/estaciones/settings, grupos/traducciones, reportes y consulta/exportación de auditoría; OpenAPI 0.3.0 no define rutas/esquemas administrativos ni de reporting. ¿Qué operaciones, permisos, filtros, respuestas y errores deben formar parte del contrato para estos paquetes? | Abierta; bloquea diseño/API de WP-BE-80/81/82 | No inventar rutas ni esquemas. Arquitectura debe contrastar criterios AC-CU-40…46 y entregar contrato aprobado + CHANGELOG antes de implementar. La lectura de menú/opciones y disponibilidad existente no equivale a administración. |
| Q-27 | 2026-10-13 | BE → ARQ | WP-BE-52 debe incrementar `catalogVersion` al cambiar disponibilidad; `CatalogController` hoy lo fija en `2L`. El contrato de producto además exige emitir `catalog.updated`, RN-AUD exige auditoría transaccional, pero no existe `event_log`/publisher (previsto para Fase 6/WP-BE-60); el endpoint de opción no explicita el evento aunque RN-OPT-07 también exige incrementar la versión. ¿Debe WP-BE-52 incorporar ya persistencia/versionado y outbox/evento transaccional, o esperar a WP-BE-60? ¿Se emite el mismo evento por cambios de opción? | Abierta; bloquea WP-BE-52 | No actualizar disponibilidad parcialmente: de otro modo el menú conserva ETag/catálogo obsoleto o se pierde `catalog.updated`. Evidencia: RN-CAT-05, RN-OPT-07/09, RN-AUD-01/05, OpenAPI `PATCH /staff/products/{productId}/availability` y `PATCH /staff/options/{optionId}/availability`, `CatalogController` (versión constante), `tiempo-real.md` §4 (inserción transaccional en event_log) y ausencia del módulo. |
| Q-28 | 2026-10-13 | BE → dueño/ARQ | `POST /staff/sessions/{sessionId}/join-code/rotate` no declara `Idempotency-Key` en OpenAPI; repetirlo rota el código otra vez, mientras WP-BE-51-T02 pide idempotencia para comandos. ¿Se agrega el header al contrato (y CHANGELOG) o se aprueba explícitamente que esta operación no sea idempotente? | Resuelta por el dueño (2026-10-13) | Agregar `Idempotency-Key` requerido, UUID v4, al contrato y documentarlo en CHANGELOG antes de implementar la rotación. |
| Q-29 | 2026-10-13 | BE → dueño | La revocación contractual usa permiso `participant.revoke`, pero su acción `PARTICIPANT_REVOKED` sigue propuesta 🟡 en el catálogo de auditoría. ¿Se confirma ese código para implementar la ruta? | Resuelta por el dueño (2026-10-13) | Confirmó `PARTICIPANT_REVOKED` y que el evento se escriba en la misma transacción que la revocación, conforme a RN-AUD-05. OpenAPI prevalece sobre la mención obsoleta `participant.manage` de la ficha. |
| Q-30 | 2026-10-13 | BE → dueño/ARQ | El catálogo nombra actores `USER`/`PARTICIPANT`, mientras V7 restringe `audit_event.actor_type` a `STAFF`/`CUSTOMER`/`SYSTEM` y los usos existentes persisten esos últimos. ¿Se confirma esa equivalencia de valores? | Resuelta por el dueño (2026-10-13) | Mapear `USER` → `STAFF` y `PARTICIPANT` → `CUSTOMER` solo en persistencia; conservar RN-AUD y V7 sin migración. `SYSTEM` no cambia. |

### Q-24 — evidencia y decisión final

**Fuentes oficiales:** [RN-PRE-08/RN-PRE-10](../02-dominio/reglas-negocio.md), [transiciones de ítems](../02-dominio/estados-y-transiciones.md), [modelo de datos](../02-dominio/modelo-datos.md), [OpenAPI](../04-api/openapi.yaml), migraciones V9/V10 y [ficha WP-BE-50](../08-agentes/backend-work/wp-be-50.md).

- **FIFO.** RN-PRE-08 fija `acceptedAt`; aceptación asigna el mismo instante a `acceptedAt` y `queuedAt`, y el undo conserva `queuedAt` (H-22). La consulta ordena `acceptedAt ASC NULLS LAST, itemId ASC`: el ID desempata empates reales y estabiliza el grupo histórico sin `acceptedAt`; para ese grupo no se afirma FIFO. Cursor y documentación usan esta misma clave.
- **Históricos y SLA.** V9 `accepted_at` y V10 `queued_at` son nullable sin backfill. `queuedAt` se expone nullable y conserva solo el dato persistido. `delayed` se evalúa como `acceptedAt + delayedItemSlaMin < Clock.instant()` (configurable, default 10, límite estricto); es `null` si falta `acceptedAt`, nunca `false` por desconocimiento. No inferir fechas de `created_at` ni de otros ítems. Los dos campos se mantienen presentes en la respuesta, con valor JSON `null` cuando corresponda.
- **Cursor y concurrencia — enfoque MVP acordado:** consistencia eventual, sin snapshot transaccional entre requests. Keyset con `acceptedAt ASC NULLS LAST, itemId ASC` es estable para un conjunto sin cambios. Una inserción que quede después de la clave puede aparecer en la página siguiente; una inserción antes del cursor no aparecerá en ese recorrido. Cambios de estado pueden quitar filas pendientes o alterar pertenencia entre páginas; refrescar la cola converge. No se promete recorrido completo ni ausencia de omisiones ante concurrencia. El cursor opaco se versiona; cursores previos al nuevo orden se rechazan para no continuar con una clave distinta.
- **Métricas — pospuestas:** RN-PRE-10 y estados exigen guardar timestamps para métricas, no agregados concretos. Existen timestamps de transición; `accepted_at` y `queued_at` históricos pueden faltar y no son reconstruibles con fiabilidad. No agregar métricas hasta definir objetivo, fórmula, período, unidad, población y tratamiento de datos desconocidos.
- **Snapshots y estados.** La cola trabaja con ítems activos de pedidos aceptados y sesión abierta; nombres/opciones son snapshots, no deben reconstruirse desde el catálogo actual. Se preserva el filtro de estados y la autorización por restaurante/estación; esto no altera transiciones, CAS/versionado ni auditoría del agregado.

## 6. Protocolo de cambios

1. **Cambio de regla de negocio / estado / error / endpoint** → se modifica primero el documento fuente. Se anota en `04-api/CHANGELOG.md` si afecta al contrato.
2. **Cambio compatible** (campo opcional nuevo, error nuevo): aviso a Web y Android; no rompe.
3. **Cambio incompatible** (renombrar, quitar, cambiar semántica): requiere ADR o nota en decisiones, versión nueva del contrato y PR coordinado (backend + clientes).
4. Los agentes **no** cambian por su cuenta nombres de estados, errores ni respuestas.
