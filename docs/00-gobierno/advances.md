# Advances — bitácora y contexto para el próximo agente

> **Léelo justo después de `AGENTS.md`.** Dice dónde está el proyecto, qué se decidió, qué falta y qué paso toca.
> **Regla de uso:** antes de cada cambio, marcar el paso como 🔄 y listar los archivos que se van a tocar; al terminar, marcarlo ✅ y agregar una entrada en la bitácora (sección 6).
> Cada cambio se anota con tres partes: **Qué** se hizo, **Por qué**, y **Qué estudiar** (el dueño del proyecto está aprendiendo).

Leyenda: ✅ hecho · 🔄 en curso · ⬜ pendiente · 🟡 propuesto · 🔴 bloqueado

---
## 1. Estado actual (snapshot al 2026-10-11; secciones 1.1 y 1.2 actualizadas el 2026-10-12 con el estado del backend)

### 1.1 Qué existe
| Pieza | Estado | Dónde |
|---|---|---|
| Plan maestro original | ✅ | `Plan_Maestro_Sistema_Cafeteria.md/.pdf` |
| Documentación v0.1 (producto, dominio, arquitectura, API, diseño, pruebas, despliegue, agentes) | ✅ | `docs/` |
| Documentación v0.2 (opciones de producto, propina, ajustes, i18n, llamados al mozo, imágenes en carpeta, multi-sucursal simulada) | ✅ | secciones "v0.2" dentro de cada doc |
| Decisiones D-101…D-159 y preguntas Q-01…Q-20 resueltas (Q-21 abierta) | ✅ | `docs/00-gobierno/decisiones.md` |
| ADRs 0001–0009 (propuestas) | ✅ | `docs/00-gobierno/adr/` |
| Contrato `openapi.yaml` **0.2.5** (WP-ARQ-10) | ✅ (lint de Redocly ejecutado localmente en WP-OPS-21) | `docs/04-api/openapi.yaml` |
| Config de lint `redocly.yaml` | ✅ (clave corregida, H-09) | raíz |
| `CHANGELOG.md` del contrato hasta 0.2.5 | ✅ | `docs/04-api/CHANGELOG.md` |
| Estructura de carpetas y `AGENTS.md` por carpeta | ✅ | `backend/`, `web/`, `android/` |
| Entorno local WP-OPS-20: Compose, variables de ejemplo y plantillas PR/issue | ✅ validado desde un clon temporal limpio en Windows | `infra/`, `.github/` |
| CI WP-OPS-21: backend, docs y escaneo de secretos; estados explícitos para Web/Android sin proyectos | 🔄 ejecución compartida: Backend CI, Documentation CI y Secret scanning verdes; Web/Android fallan explícitamente porque aún no existen sus proyectos | `.github/workflows/`, `.markdownlint-cli2.yaml`, `.gitleaksignore` |
| Marca | 🔄 Se recibió `design/img/ic_logo.svg`; el dueño confirmó usarlo sin rediseño. El icono y boceto anteriores fueron retirados por el dueño; falta icono de app independiente | `design/img/` |
| Backend: **dominio puro** de WP-BE-10 (`Money`, `Payment`/`Bill`, ajustes, reembolsos/anulaciones, `Order`/`OrderItem`, `TableSession`, `JoinCode`). JDK 25, Gradle 9.8.1, Kotlin 2.4.0, JUnit 6.1.2 | ✅ 235 pruebas; 0 fallos, errores u omitidas; cobertura de líneas 97,61 % (corrida final del 2026-10-12) | `backend/` |
| Backend ejecutable WP-BE-20: Spring, perfiles, Actuator, `/version`, Problem+JSON, `Clock`, Testcontainers y ArchUnit base | ✅ `clean check jacocoTestReport`: 242 pruebas, 0 fallos/errores/omitidas; cobertura de líneas 715/750 (95,33 %) | `backend/` |
| WP-BE-21: migración Flyway V1 y semilla obligatoria | ✅ `clean check jacocoTestReport`: 243 pruebas, 0 fallos/errores/omitidas; cobertura 715/750 (95,33 %) | `backend/` |
| WP-UX-10: identidad visual y tokens | ✅ Paleta aprobada, tokens publicados y pares principales con contraste WCAG AA | `design-tokens.json`, `docs/05-diseno/sistema-visual.md` |
| Carta demo | ✅ Recibida: 47 productos, 11 subcategorías, precios de demostración en ARS | Insumo para WP-BE-30 |
| Horario del restaurante | ✅ Recibido; zona horaria pendiente de confirmar antes de persistirlo | Apertura y cierre semanal |

### 1.2 Qué NO existe todavía
- Código de `web/` y `android/` (solo tienen `AGENTS.md`). El backend ya tiene runtime Spring y endpoints operativos de versión/salud, pero todavía no incluye API de negocio ni persistencia.
- Persistencia de entidades y flujos de negocio posteriores a la base inicial WP-BE-21; los proyectos ejecutables de Web y Android (WP-WEB-20 y WP-AND-20). Sus checks informan que la validación no se ejecutó hasta que existan.
- No hay un isotipo independiente para favicon/Android; `design/img/ic_logo.svg` queda sin editar y la Q-21 sigue abierta solo para ese derivado.
- Catálogo de acciones de auditoría.
- Pruebas de contrato, concurrencia, web y Android; WP-BE-20 ya incorpora integración con PostgreSQL Testcontainers y una regla base de ArchUnit.

### 1.3 Advertencias para quien continúe
1. **La fuente de verdad es este repositorio local.** Cualquier copia en otro lado (zips, sandboxes, descargas) puede estar desactualizada. En particular, **no sobrescribir** `CHANGELOG.md`, `openapi.yaml` ni `reglas-negocio.md` con versiones viejas.
2. Existen **copias duplicadas en la raíz** (`decisiones.md`, `cambios-v0.2.md`, `roadmap-fases.md`, `README.md`) además de las de `docs/`. La versión oficial es la de `docs/` (ver Paso 7).
3. Las secciones "v0.2" están **apendadas al final** de varios documentos y tienen precedencia sobre el texto anterior. Se integrarán en el Paso 8.
4. Nadie debe cambiar nombres de estados, errores o campos del contrato por su cuenta (`AGENTS.md` §2).

### 1.4 Traspaso al siguiente agente (2026-10-12)
- **Paquetes locales listos:** WP-BE-20/21 y WP-UX-10; este avance reúne runtime Spring, configuración, migración/semillas, pruebas y tokens de diseño. No implica que exista API de negocio, catálogo ni UI.
- **WP-OPS-21 sigue abierto:** en GitHub están verdes Backend CI, Documentation CI y Secret scanning; Web/Android fallan explícitamente porque aún no existen sus proyectos. No declarar toda la CI verde ni cerrar el paquete hasta revisar sus checks reales.
- **Instrucción para el próximo agente:** tomar únicamente WP-UX-11. WP-UX-10 ya está completo y aporta identidad/tokens; los wireframes son la dependencia de diseño que falta para construir las interfaces sin inventar flujos. Leer `docs/05-diseno/mapa-pantallas.md`, `docs/05-diseno/sistema-visual.md`, `docs/08-agentes/ux-ui.md` y el DoD de WP-UX-11 en `docs/08-agentes/roadmap-fases.md`.
- **Qué entregar en WP-UX-11:** wireframes de baja fidelidad para todas las pantallas y superficies vigentes del mapa, matriz de cobertura y plantillas de estados aplicables. No implementar Web/Android ni avanzar otro paquete; al terminar, registrar evidencia y el siguiente paso según dependencias del roadmap.
- **Insumos y límites:** carta ficticia de 47 productos con precios demo en ARS, aún no cargada; confirmar zona horaria antes de persistir horarios. Mantener `design/img/ic_logo.svg` intacto y no derivar un isotipo sin resolver Q-21/autorización.
- **Validación local más reciente:** `.\backend\gradlew.bat -p backend check` no pudo completar Testcontainers porque Docker Desktop informó que no puede iniciar; 239 pruebas reportadas, con error de inicialización en `BackendSmokeTest`. No es un resultado verde; repetir cuando Docker esté disponible.
- **Regla de continuidad:** leer `AGENTS.md` y esta bitácora antes de tocar archivos; respetar la jerarquía documental, preservar cambios existentes y no hacer commit/push sin autorización.

---
## 2. Decisiones tomadas hoy (D-142 … D-157)

El dueño delegó: *"toma las mejores decisiones posibles para que sea escalable y bueno"*. Estas decisiones las toma el Arquitecto; el dueño puede revocar cualquiera. Se registrarán formalmente en `decisiones.md` en el Paso 1.

### D-142 — Límite de descuentos del Mozo: **15 %**, acumulado, configurable
- **Qué:** el Mozo puede aplicar ajustes hasta el 15 % de los **cargos brutos** de la sesión, sumando **todos** los ajustes que hizo sin `adjustment.apply_any`. Se guarda en `restaurant_settings.maxStaffDiscountPercent`. El tope absoluto `maxStaffAdjustmentMinor` queda **opcional** (sin tope por defecto). El Dueño no tiene límite.
- **Por qué:** el 15 % ya está en `reglas-negocio.md` y en el contrato (cambiar menos = menos riesgo). Que sea *acumulado* evita el truco de aplicar muchos descuentos chicos. Que sea *configurable* permite ajustarlo sin volver a programar.
- **Alternativas:** 10 % (la propuesta inicial, descartada por ser más restrictiva sin motivo); límite por cada descuento (se puede eludir); límite fijo en el código (no escala).
- **Estudiar:** *límites de autorización por rol* (autorización ≠ solo "puede/no puede": también "hasta cuánto"), *configuración vs. constantes en el código*.

### D-143 — HOST en sesiones abiertas por el personal: **el primero en unirse**, de forma atómica
- **Qué:** una sesión abierta por el personal nace **sin HOST**. El primer participante que se une pasa a ser `HOST`; los siguientes son `MEMBER`. La base garantiza **un solo HOST** con el índice único parcial; si dos personas se unen a la vez, el `INSERT` del segundo como HOST falla por la restricción y se reintenta como `MEMBER` dentro de la misma transacción. Mientras no haya HOST, el personal conserva `participant.revoke` y rotar código.
- **Por qué:** el contrato ya describe este comportamiento. Es natural: el mozo dicta el código y quien lo recibe es quien "maneja" la mesa. La garantía la da la **base de datos**, no el código de la aplicación.
- **Alternativas:** el mozo designa al HOST (más fricción); un HOST ficticio del personal (ensucia los datos y el historial).
- **Estudiar:** *condición de carrera (race condition)*, *índice único parcial* (`WHERE role = 'HOST'`), *"intentar y manejar el conflicto"* vs. *"consultar y luego insertar"* (el segundo método no es seguro).

### D-144 — Cómo se resuelve el restaurante en endpoints públicos
- **Qué:** (a) los endpoints con `tableCode` obtienen el restaurante **de la mesa**, nunca de un parámetro; (b) `GET /menu`: con token, manda el restaurante del token (si `restaurantId` difiere → `404`); sin token, usa `restaurantId` y, si falta, el predeterminado del despliegue (`DEFAULT_RESTAURANT_ID`); un id desconocido → `404`. (c) Se agrega a `PublicTable` el campo opcional `restaurantId` para que la web pida el menú del local correcto tras escanear el QR.
- **Por qué:** el cliente nunca debe poder elegir libremente "de quién" es el dato (aislamiento multi-sucursal, RN-TEN). Agregar un campo opcional es un cambio **compatible**.
- **Estudiar:** *multi-tenancy*, *cambios aditivos vs. incompatibles* de un contrato.

### D-145 — Campos "proposed" del contrato quedan **aceptados**
- **Qué:** `Participant.revoked`, `Order.rejection`, los campos de `StaffTable` y `StaffOpenSessionResponse` pasan de "proponer" a definitivos. `revoked` es opcional; `rejection` aparece solo en pedidos rechazados.
- **Por qué:** sin `rejection` el cliente no puede mostrar el motivo (exigido por CU-08/CU-10); sin `revoked` el personal no distingue accesos retirados. Ninguno rompe a clientes existentes.

### D-146 — Pago solo propina: se corrige la regla (el contrato tenía razón)
- **Qué:** RN-BIL-03 pasa a aplicar **solo cuando `amountMinor > 0`**. Con `amountMinor = 0` rige RN-TIP-01 (pago solo propina), que no se rechaza por saldo 0.
- **Por qué:** dos reglas se contradecían; el contrato y RN-TIP expresan lo que se decidió (D-130).
- **Estudiar:** *consistencia entre documentos* (la regla más específica debe estar reflejada en la general).

### D-147 — `TIP_NOT_ALLOWED` se mantiene en **409**
- **Qué:** se deja como en el catálogo de errores (conflicto de estado: no hay cargos). El contrato lo declara en la respuesta `409` del pago, no en `422`.
- **Por qué:** 409 = "los datos están bien, pero el estado actual no lo permite"; 422 = "los datos violan una regla de formato/negocio". Aquí es estado.

### D-148 — **Redocly CLI** como única herramienta de lint, vista previa y empaquetado
- **Qué:** se reemplaza Spectral por Redocly en ADR-0008, estrategia de pruebas, CI (`nube.md`), `convenciones.md` y guía DevOps. `redocly.yaml` se verifica corriendo el lint.
- **Por qué:** ya está en el repo; una sola herramienta hace lint + documentación HTML + `bundle`. Menos dependencias = menos mantenimiento.

### D-149 — **Una sola copia** de cada documento
- **Qué:** la copia oficial vive en `docs/`. Las copias de la raíz se comparan y el dueño las borra (Git conserva el historial).
- **Por qué:** dos copias divergen tarde o temprano y los agentes leen cualquiera. *DRY* (no repetirse) también aplica a la documentación.

### D-150 — Formato del `CHANGELOG.md`
- **Qué:** más reciente arriba, con fecha, con la convención `0.x` = borrador y `1.0.0` = contrato congelado al cerrar la Fase 1. `info.version` del OpenAPI debe coincidir con la última entrada.
- **Estudiar:** *versionado semántico (SemVer)*.

### D-151 — Catálogo de acciones de auditoría
- **Qué:** nuevo `docs/02-dominio/catalogo-auditoria.md` con todos los códigos (`ORDER_ACCEPTED`, `PAYMENT_VOIDED`…), quién los genera y qué datos guardan.
- **Por qué:** auditar exige un vocabulario cerrado; sin él cada módulo inventa el suyo y los reportes se rompen.

### D-152 — Idiomas (Q-16)
- **Qué:** en el MVP la **interfaz** de la web cliente sale en `es-AR` y `en`. Las **traducciones del menú** son opcionales: si falta una, fallback a `es-AR`. `de` queda para después.
- **Por qué:** no bloquea el lanzamiento esperando traducir todo, y el modelo ya soporta agregarlas gradualmente.

### D-153 — Propina (Q-18)
- **Qué:** campo libre + montos rápidos como **ayuda de UI** (el servidor solo recibe `tipMinor`). Sin atribución a un empleado en el MVP.
- **Por qué:** la propina sigue siendo *libre* (D-130); los chips solo ahorran tipeo.

### D-154 — Se confirma D-121 (Q-19)
- **Qué:** tests de contrato con `swagger-request-validator` + mocks con Prism desde el OpenAPI.
- **Por qué:** es lo que hace efectivo el "contrato primero": si el servidor se desvía del YAML, falla un test.

### D-155 — Imágenes (Q-20)
- **Qué:** subir imágenes desde el admin solo en local (`MEDIA_UPLOAD_ENABLED=true`); en la demo se usan las imágenes versionadas en el repo.
- **Por qué:** el hosting gratuito suele tener disco efímero: lo subido se perdería al reiniciar.

### D-156 — Backend empaquetado como imagen Docker, agnóstica del proveedor (Q-15)
- **Qué:** el backend se entrega como imagen Docker configurada solo por variables de entorno. El proveedor de hosting y PostgreSQL se elige en la Fase 2 con el checklist de `nube.md`.
- **Por qué:** una imagen corre igual en casi cualquier lado: no quedamos atados a un proveedor gratuito que puede cambiar sus planes.
- **Estudiar:** *contenedores*, *"The Twelve-Factor App"* (configuración por entorno).

### D-157 — Consolidar la documentación antes de la Fase 2 ("v0.3")
- **Qué:** integrar las secciones "v0.2" apendadas dentro del cuerpo de cada documento.
- **Por qué:** una regla repartida en dos lugares con "precedencia" es una trampa para quien llega nuevo.

### D-158 — Pago mínimo con saldo chico (H-13)
- **Qué:** el monto principal debe cumplir `min(minPaymentMinor, balance) ≤ amount ≤ balance`. Si el saldo es menor al mínimo, se paga **exactamente el saldo**.
- **Por qué:** con la regla anterior, si quedaban 50 centavos y el mínimo era 100, ningún monto era válido y la cuenta no se podía saldar. El mínimo existe para evitar pagos ridículamente chicos, no para impedir cerrar la cuenta.
- **Alternativas:** bajar el mínimo a 1 centavo (permite spam de micropagos); permitir saldar solo en efectivo (obliga al personal a intervenir por centavos).
- **Estudiar:** *casos borde en límites numéricos*, *reglas con excepciones explícitas*.

### D-159 — Cierre forzado con saldo negativo (H-14)
- **Qué:** en el cierre forzado, tras cancelar ítems, si `balance < 0` el sistema registra **en la misma transacción** un reembolso simulado automático por el exceso (sin devolver propina), auditado con el actor. Si `balance > 0` queda como **saldo no cobrado** en auditoría y comprobante. Los pagos y propinas ya registrados se conservan.
- **Por qué:** el cierre normal exige saldo 0, pero el forzado existe para destrabar situaciones. No debe dejar una sesión cerrada con plata "a favor" del cliente sin registrar. Hacerlo automático y en la misma transacción evita estados intermedios.
- **Alternativas:** bloquear el cierre forzado hasta reembolsar a mano (contradice su propósito); cerrar y dejar el saldo negativo (la contabilidad queda inconsistente).
- **Estudiar:** *transacciones*, *valores derivados vs. guardados* (el saldo no cobrado se calcula, no se guarda).

### Sigue pendiente
- **Q-21 (parcialmente resuelta):** el ícono existe como `ic_icon.svg` y hay un boceto `boceto_v.1.0.png`. Falta confirmar: (a) si el SVG es el isotipo **final**; (b) si hay logo horizontal; (c) el motivo de su tamaño (1,54 MB es muy grande para un SVG: normalmente un ícono pesa decenas de KB; puede tener una imagen incrustada o un trazado automático con miles de nodos y habría que **optimizarlo** antes de usarlo como favicon o ícono de Android). Bloquea solo los tokens y el ícono derivado, no el resto.

---
## 3. Hallazgos de la revisión (qué paso los resuelve)

| ID | Hallazgo | Paso |
|---|---|---|
| H-01 | 15 % vs 10 % (reglas y contrato dicen 15 %; el ejemplo de error del contrato dice 10; Q-17 sigue abierta en decisiones) | 1 ✅, 3 ✅, 4 ✅ |
| H-02 | RN-BIL-03 contradice el pago solo propina | 2 |
| H-03 | Regla de HOST en sesión abierta por el personal no está documentada (choca con RN-ACC-10, estados §1, modelo de datos, CU-30) | 2 ✅, 3 ✅ |
| H-04 | Resolución del restaurante en `GET /menu` no está en RN-TEN | 2 ✅, 4 ✅ |
| H-05 | Campos marcados `proposed` en el contrato | 4 ✅ |
| H-06 | RN-CAT-08 cita Q-09; RN-ORD-02/03/04 sin opciones; RN-SES-06 sin pagos/propinas; RN-BIL-06/07/09 sin propina; RN-TIP-03 sin "bruto"; RN-BIL-05 sin aclarar `.13` sobre el monto principal; encabezado de valores por defecto sin los parámetros v0.2 | 2 |
| H-07 | `CHANGELOG.md` en orden inverso al estándar, sin fechas, con una nota obsoleta | 4 ✅ |
| H-08 | `TIP_NOT_ALLOWED` y `getReceipt` (sin `x-permission`) en el contrato; etiqueta del pedido a nombre de la mesa | 4 ✅ (ver H-16) |
| H-09 | `redocly.yaml`: la clave `no-server-example-com` posiblemente es `no-server-example.com` | 5 ✅ |
| H-10 | Archivos duplicados en la raíz | 7 |
| H-11 | Q-16…Q-20 siguen abiertas en `decisiones.md` | 1 (✅ resuelto) |
| H-12 | `AGENTS.md` §1 punto 3 dice que `openapi.yaml` "cuando exista, Fase 1" y que antes rige `endpoints.md`, pero el contrato ya existe (0.2.1). El encabezado del `README.md` solo cita D-101…D-128 y Q-01…Q-14 | 8 |
| H-13 | **Regla del pago mínimo con saldo chico.** RN-BIL-03 exigía `amount ≥ minPaymentMinor` y `amount ≤ balance`; con saldo menor al mínimo ninguna cantidad servía. **Resuelto con D-158** | 3 ✅, 4 ✅ |
| H-14 | **Cierre forzado con saldo negativo** (RN-SES-06): no estaba definido qué pasaba. **Resuelto con D-159** (reembolso simulado automático) | 3 ✅, 4 ✅ |
| H-15 | **Documentos que no revisé en el Paso 3** y pueden seguir mencionando el 10 %, el mínimo fijo de 100 o un HOST siempre presente: `04-api/endpoints.md`, `05-diseno/mapa-pantallas.md`, `08-agentes/*`, ADRs y `01-producto/alcance.md`. Mi herramienta busca nombres de archivo, no contenido, así que no pude comprobarlo. **El dueño puede verificarlo con** `git grep -n -E "10 ?%|Q-15|Q-17|Q-18|Q-20|HOST" -- docs` | 8 |
| H-16 | **`Order.createdBy` en un pedido tomado por el personal** (`createOrderOnBehalf`, CU-31). El contrato solo dice que `createdBy` "se omite para Cocina"; no dice qué lleva (o si se omite) cuando no hay participante. Encontrado al revisar H-08 | A decidir (dueño) |
| H-17 | **Operaciones del invitado sin `x-permission`:** `getCurrentSession`, `listParticipants` y `listSessionWaiterCalls` (verificado leyendo el YAML). `info.description` dice que cada operación lo indica | A decidir (dueño) |
| H-18 | **Reembolso automático del cierre forzado (D-159):** `Payment` no tiene un campo que lo distinga (la marca "automático por cierre forzado" vive solo en la auditoría). El contrato documenta que queda como `REFUND` | A decidir antes de la Fase 7 |
| H-19 | **`limitMinor` de `ADJUSTMENT_LIMIT_EXCEEDED`:** `catalogo-errores.md` lo lista como extra pero no define qué representa. En el ejemplo se asumió "15 % de los cargos brutos" (ver Paso 4) | A decidir (dueño) |
| H-20 | **Acciones de auditoría propuestas 🟡:** `RN-AUD-01` exige auditar revocación de participantes, catálogo, usuarios/roles/estaciones, QR, ajustes y logins fallidos, pero ningún documento nombraba esos códigos. Propuestos en `catalogo-auditoria.md` §3 (`PARTICIPANT_REVOKED`, `CATALOG_CHANGED`, `USER_CHANGED`, `TABLE_QR_REGENERATED`, `SETTINGS_CHANGED`, `LOGIN_FAILED_REPEATED`). Tampoco se audita hoy: llamados al mozo ni rotación del `joinCode` | A confirmar (dueño) |
| H-21 | **Pedido tomado por el personal (RN-ORD-18):** nace `ACCEPTED` y se audita con `ORDER_CREATED` (`after.status = ACCEPTED`); no se emite `ORDER_ACCEPTED` aparte. Es una interpretación mía: ningún documento lo dice | A confirmar (dueño) |
| H-22 | **Deshacer tras el atajo `QUEUED → READY`:** al deshacer vuelve a `IN_PREPARATION` con `startedAt` vacío; `estados-y-transiciones.md` §3 no dice qué hacer | A confirmar (dueño) |
| H-23 | **Prioridad de errores en un pago:** si fallan a la vez el monto principal y la propina (o hay pago vacío), el catálogo no dice cuál se informa. Se eligió: vacío (`TIP_INVALID`) → monto → propina | A confirmar (dueño) |
| H-24 | **Prioridad de errores en un ajuste:** si fallan a la vez el saldo y el límite del Mozo, el catálogo no dice cuál se informa. Se eligió: inválido → saldo → límite (`AdjustmentRules.validate`) | A confirmar (dueño) |
| H-25 | **`ADJUSTMENT_ITEM_INVALID` (cortesía):** exige saber si el ítem está cancelado o ya tiene cortesía; el dominio todavía no tiene el snapshot completo de `OrderItem` con id y precio | Tanda 4 ✅ (`CourtesyRules`) |
| H-26 | **Aceptación y expiración:** el TTL lo aplica exclusivamente el job; `accept` valida el estado actual y acepta si sigue `PENDING_REVIEW`, aunque haya superado el TTL. Si el job ya lo marcó `EXPIRED`, `accept` responde `ORDER_EXPIRED` | Confirmada por producto e implementada |
| H-27 | **Transición inválida y carrera del job:** el dominio informa el error correspondiente; `expire` antes del TTL es error de programación (`IllegalArgumentException`). El job futuro debe usar actualización condicional y no sobrescribir pedidos decididos | Confirmada por producto; dominio implementado, infraestructura pendiente en WP-BE-40 |
| H-28 | **Límite del TTL de pedidos:** el vencimiento es inclusivo (`elapsed >= ttl`); el job puede expirar justo al cumplirse el TTL | Confirmada por producto e implementada |
| H-29 | **Cortesía sobre un ítem de un pedido que aún no se aceptó:** RN-ADJ-04 solo pide "no cancelado y sin cortesía previa"; un ítem `PENDING` cumple eso pero todavía no es un cargo facturable (RN-BIL-01). **Corrección aplicada el 2026-10-12:** RN-ADJ-04 exige ítem aceptado y `CourtesyRules` rechaza `PENDING` con `ADJUSTMENT_ITEM_INVALID`, con 2 pruebas nuevas | **Verificado en código y regla**; documentación alineada en contrato 0.2.3. El lint de Redocly sobre el contrato vigente 0.2.5 se ejecutó localmente en WP-OPS-21. |
| H-30 | **Matriz de transiciones inválidas de `Order`:** faltaban estados derivados y su cobertura. `Order` ahora delega transiciones de ítems, deriva su propio estado, y `OrderTest` prueba decisiones inválidas desde `IN_PREPARATION`, `READY` y `DELIVERED`, además de los demás estados decididos | Resuelto en WP-BE-10; suite completa verificada |

---
## 4. Plan paso a paso

**Método:** un paso por vez, con aprobación del dueño antes de pasar al siguiente. Al terminar cada paso se sugiere **un commit** (Conventional Commits) para que el historial de Git cuente la historia del proyecto.

| Paso | Estado | Qué se hace | Archivos | Terminado cuando | Commit sugerido |
|---|---|---|---|---|---|
| 0 | ✅ | Crear este archivo (estado, decisiones, plan) | `docs/00-gobierno/advances.md` | El archivo existe y el dueño lo aprobó | `docs(governance): add advances log` |
| 1 | ✅ | Registrar D-142…D-157; cerrar Q-15…Q-20; dejar Q-21; enlazar `advances.md` desde `AGENTS.md` y `README.md` | `docs/00-gobierno/decisiones.md`, `AGENTS.md`, `README.md` | Cada decisión tiene ID, estado y motivo; no quedan preguntas contradictorias | `docs(governance): record decisions D-142..D-157` |
| 2 | ✅ | Corregir reglas de negocio (H-02, H-03, H-04, H-06) y agregar la regla de HOST y RN-TEN-03 | `docs/02-dominio/reglas-negocio.md` (+ `docs/00-gobierno/advances.md` para anotar) | Ninguna regla contradice a otra ni al contrato | `docs(domain): align business rules with contract` |
| 3 | ✅ | Propagar a documentos que dependen de esas reglas (H-01, H-03): estados §1, casos de uso (CU-02/03/30), modelo de datos, roles (15 %), casos límite nuevos, errores, criterios de aceptación | `estados-y-transiciones.md`, `casos-de-uso.md`, `modelo-datos.md`, `actores-y-roles.md`, `casos-limite.md`, `catalogo-errores.md`, `criterios-aceptacion.md`; además `decisiones.md` y `reglas-negocio.md` (D-158 y D-159, que cierran H-13 y H-14) | Cada regla modificada se refleja en todos los documentos que la mencionan | `docs(domain): propagate rule changes` |
| 4 | ✅ | Contrato **0.2.2**: ejemplo 15 %, quitar "proposed", `PublicTable.restaurantId`, `TIP_NOT_ALLOWED` en 409, `x-permission` del recibo, regla del HOST en `join`, etiqueta del pedido a nombre de la mesa; **y reflejar D-158** (`minMinor` del pago calculado con el saldo) **y D-159** (efectos del `force-close`). Reordenar `CHANGELOG.md` (H-05, H-07, H-08) | `docs/04-api/openapi.yaml`, `docs/04-api/CHANGELOG.md` | `info.version` = última entrada del changelog; todos los cambios son **compatibles** | `docs(api): contract 0.2.2` |
| 5 | ✅ | Redocly: corregir `redocly.yaml` (H-09) y reemplazar Spectral en los documentos (D-148). **El dueño corre** `npx @redocly/cli lint docs/04-api/openapi.yaml` y pega el resultado | `redocly.yaml`, ADR-0008, `estrategia.md`, `nube.md`, `convenciones.md`, `devops.md` | El lint corre sin errores de configuración | `chore(api): standardize on redocly` |
| 6 | ✅ | Catálogo de acciones de auditoría (D-151) | `docs/02-dominio/catalogo-auditoria.md` | Cada acción citada en reglas/estados tiene su código | `docs(domain): add audit action catalog` |
| 7 | ✅ | Comparar duplicados de la raíz con los de `docs/` y reportar diferencias; el dueño borra (H-10) | raíz | Un solo archivo oficial por documento | `chore: remove duplicated docs` |
| 8 | 🔄 | Consolidar v0.2 en el cuerpo de cada documento (D-157) | todos los docs con sección "v0.2" | Ningún documento tiene secciones con "precedencia" | `docs: consolidate v0.2 into body (v0.3)` |
| 9 | ✅ | Cerrar dominio puro de WP-BE-10 con H-26/H-27/H-28 confirmadas | `backend/src/main/kotlin/com/somphie/`, pruebas, `build.gradle.kts`, reglas/transiciones/casos límite/casos de uso/modelo de dominio/OpenAPI/CHANGELOG y esta bitácora | Suite verde (235 pruebas) y cobertura medida (97,61 %); documentación alineada. WP-UX-10 sigue pendiente por Q-21 | — |

---
## 5. Reglas de trabajo durante este tramo
1. **Un paso por vez.** No combinar pasos; no adelantarse.
2. **Antes:** marcar el paso 🔄 en la tabla y listar archivos exactos en la bitácora.
3. **Durante:** cambios pequeños; si aparece algo no previsto, **anotarlo como hallazgo nuevo** (H-xx) en vez de resolverlo en silencio.
4. **Después:** verificar (releer lo cambiado, buscar referencias rotas), marcar ✅ y escribir la entrada de bitácora con *Qué / Por qué / Qué estudiar*.
5. **No tocar el contrato** (`openapi.yaml`) fuera del Paso 4, y siempre junto con el `CHANGELOG.md`.
6. Todo cambio de regla se hace **primero en las reglas**, después en los documentos derivados y por último en el contrato (`decisiones.md` §6).

---
## 6. Bitácora

### 2026-10-11 — Paso 0 ✅ — Se crea `advances.md`
- **Qué:** se revisó el estado real del repositorio local (`openapi.yaml` 0.2.1, `redocly.yaml`, `CHANGELOG.md`, `reglas-negocio.md`, `decisiones.md`), se documentó el estado, se tomaron las decisiones D-142…D-157 y se definió el plan de 10 pasos.
- **Por qué:** el proyecto lo van a tocar varios agentes y el dueño está estudiando; sin un registro del contexto, cada agente rehace trabajo o contradice al anterior.
- **Qué estudiar:** *Architecture Decision Records* (por qué se registra el "porqué" y no solo el "qué"); *fuente única de verdad*; la diferencia entre **tomar una decisión** y **registrarla** (D-142…157 todavía no están en `decisiones.md`: eso es el Paso 1).
- **Archivos tocados:** solo este archivo (nuevo).

### 2026-10-11 — Corrección al Paso 0 — los archivos de marca sí existen
- **Qué:** corregí `advances.md`: había afirmado que `design/img/` solo tenía su `README.md` y que faltaba `ic_icon.png`. El dueño aclaró que el ícono está como `ic_icon.svg`; al verificar, la carpeta contiene `ic_icon.svg` (1,54 MB), `boceto_v.1.0.png` (1,75 MB) y `README.md`.
- **Por qué fue un error:** el listado que usé al principio era anterior a que se agregaran los archivos y no lo volví a verificar antes de afirmar "no existe". Regla para el agente: **volver a listar la carpeta antes de decir que algo falta**.
- **Qué quedó sin hacer:** no pude abrir ninguno de los dos archivos (el límite de lectura es 1 MB), así que no sé cómo se ven ni qué contiene el SVG. No se asume nada sobre su contenido.
- **Qué estudiar:** *un SVG es texto (XML)*: si pesa mucho es porque tiene muchos nodos o una imagen en base64 incrustada; herramientas como **SVGO** lo reducen; Android no usa SVG directamente sino *VectorDrawable*, que importa con Vector Asset Studio y tiene límites de complejidad.
- **Archivos tocados:** solo `advances.md`.

### 2026-10-11 — Paso 1 ✅ — Se registran D-142…D-157 y se cierran Q-15…Q-20
- **Qué:**
  - `decisiones.md`: nueva sección 3.2 con D-142…D-157 (estado 🟢 *delegada*, revocable); D-121 pasa a 🟢 (confirmada en D-154); Q-15…Q-20 pasan a *Resueltas* con enlace a su decisión; en *Pendientes* queda solo Q-21, con el texto actualizado (el ícono existe como SVG); D-133 apunta a D-156 en vez de a Q-15, que ya está cerrada.
  - `AGENTS.md` y `README.md`: aviso al comienzo para leer `docs/00-gobierno/advances.md` antes de tocar nada.
  - Hallazgo nuevo **H-12** anotado (no se resolvió ahora, va al Paso 8).
- **Por qué:** una decisión que solo vive en un archivo de bitácora no manda: `AGENTS.md` dice que la jerarquía de la verdad empieza en `decisiones.md`. Ahora las decisiones tienen su lugar oficial y los agentes que lean `AGENTS.md` llegan a `advances.md`.
- **Qué NO se hizo (a propósito):** no se tocaron `reglas-negocio.md` ni `openapi.yaml`, ni los textos de H-01 (15 % vs 10 %), que siguen vigentes hasta los pasos 2–4. D-134 todavía nombra `ic_icon.png` (Paso 9). La copia duplicada de `decisiones.md` en la raíz **no** se actualizó: ya diverge de la oficial, y eso es justo lo que el Paso 7 va a resolver.
- **Qué estudiar:** *ADR / registro de decisiones* (qué distingue "decidido" de "registrado"); *trazabilidad* (cada pregunta cerrada apunta a su decisión y cada decisión a su origen); por qué las referencias a algo ya cerrado ("ver Q-15") quedan como *enlaces rotos* conceptuales.
- **Archivos tocados:** `docs/00-gobierno/decisiones.md`, `AGENTS.md`, `README.md`, `docs/00-gobierno/advances.md`.

### 2026-10-11 — Paso 2 ✅ — Se alinean las reglas de negocio con las decisiones y el contrato
- **Qué** (todo en `docs/02-dominio/reglas-negocio.md`):
  - **H-02 / D-146:** RN-BIL-03 aplica solo con `amountMinor > 0`; el pago solo propina ya no choca con RN-TIP-01. RN-TIP-04 lo refuerza.
  - **H-03 / D-143:** RN-ACC-10 ajustada ("HOST si existe") y nuevas **RN-ACC-14** (sesión abierta por el personal nace sin HOST; el primero en unirse lo es) y **RN-ACC-15** (un solo HOST garantizado por la base; si dos entran a la vez, el segundo queda como MEMBER sin error).
  - **H-04 / D-144:** nueva **RN-TEN-03** (cómo se resuelve el restaurante en endpoints públicos) y RN-TEN-01 la referencia.
  - **H-06:** RN-CAT-08 cita D-137 en vez de Q-09; RN-ORD-02/03/04 mencionan opciones y el snapshot completo; RN-SES-06 dice qué pasa con pagos y propinas; RN-BIL-05 aclara que el `.13` se evalúa sobre el monto principal; RN-BIL-06/07/09 mencionan propina y ajustes; RN-TIP-03 aclara "cargos brutos"; el encabezado de valores por defecto incluye los parámetros de v0.2.
  - **D-142:** RN-ADJ-06 pasa a límite **acumulado** del 15 % sobre cargos brutos, con tope absoluto opcional y sin límite para el Dueño.
  - Se quitaron marcas 🟡 y referencias a preguntas ya cerradas (D-106, D-125, Q-18 → D-153, Q-20 → D-155).
  - Se agregó al final del documento una tabla **Historial de cambios**.
- **Por qué:** las reglas son el segundo escalón de la jerarquía de la verdad (`AGENTS.md` §1). Si dos reglas se contradicen, un agente implementa una y rompe la otra. Según `decisiones.md` §6, un cambio se hace **primero en las reglas**, después en los documentos derivados y por último en el contrato.
- **Hallazgos nuevos (no se resolvieron en silencio):** **H-13** (pago mínimo mayor que un saldo chico) y **H-14** (cierre forzado con saldo negativo). Ambos son huecos que ya existían; los descubrí al redactar RN-BIL-03 y RN-SES-06.
- **Qué NO se hizo:** no se tocaron estados, casos de uso, modelo de datos ni contrato (pasos 3 y 4). RN-SES-06 quedó con una parte 🟡 a propósito: la mecánica del saldo negativo falta decidirla.
- **Qué estudiar:** *regla general vs. regla específica* (RN-BIL-03 era general y RN-TIP-01 específica: la general debe nombrar sus excepciones); *invariantes* ("un solo HOST" es una invariante: se protege en la base, no solo en el código); *casos borde en límites numéricos* (H-13 es un caso clásico: un mínimo que choca con un máximo); *saldo acumulado vs. por operación* (D-142).
- **Archivos tocados:** `docs/02-dominio/reglas-negocio.md`, `docs/00-gobierno/advances.md`.

### 2026-10-11 — Paso 3 ✅ — Se propagan las reglas a los documentos derivados; D-158 y D-159
- **Decisiones nuevas** (el dueño dijo "decidí vos"): **D-158** (pago mínimo `min(minPaymentMinor, balance)`: si el saldo es menor al mínimo, se paga exactamente el saldo) y **D-159** (cierre forzado con saldo negativo: reembolso simulado automático por el exceso, sin propina, en la misma transacción; con saldo positivo queda como *no cobrado*). Registradas en `decisiones.md` §3.2 y aplicadas en `reglas-negocio.md` (RN-BIL-03, RN-SES-06, RN-TIP-04).
- **Qué se cambió, por archivo:**
  - `estados-y-transiciones.md`: `open` (con/sin HOST según quién abre), `force_close` (efectos de D-159), pago (guardas con mínimo dinámico), reembolso automático, §8.1.
  - `casos-de-uso.md`: CU-01 y CU-05 (`restaurantId`), CU-02/03/30 (HOST), CU-12 (mínimo y pago solo propina), CU-35 (D-159), CU-37 (límite acumulado); se quitaron marcas 🟡 de D-113/D-114.
  - `modelo-datos.md`: ajuste por defecto 15 %, `max_staff_adjustment_minor` nullable, nueva §7.5 con el patrón SQL `INSERT … ON CONFLICT DO NOTHING` para el HOST.
  - `actores-y-roles.md`: límite acumulado 15 %, `participant.manage` con la salvedad del HOST ausente, se quitó la marca 🟡.
  - `catalogo-errores.md`: `PAYMENT_AMOUNT_INVALID`, `PAYMENT_NOTHING_TO_PAY` y `ADJUSTMENT_LIMIT_EXCEEDED` actualizados. **No se agregó ningún código de error nuevo.**
  - `casos-limite.md`: nuevos CL-055, 056, 118, 119, 199, 216, 217; CL-101, 102, 112, 190 corregidos.
  - `criterios-aceptacion.md`: nueva sección con AC-CU-30-a, 03-d/e, 13-a, 05-a/b, 12-j/k, 35-b/c/d, 37-e.
  - `reglas-negocio.md`: fila nueva en su historial de cambios.
- **Por qué:** cuando cambia una regla hay que actualizar **todos** los documentos que la repiten; si no, queda la misma contradicción que se corrigió en el Paso 2 pero escondida en otro archivo. Además, cada regla nueva necesita un caso límite y un criterio de aceptación: sin eso nadie escribe la prueba que protege la regla.
- **Qué NO se hizo:** no se tocó `openapi.yaml` (Paso 4); se amplió la descripción del Paso 4 para incluir D-158 y D-159. No pude verificar con búsqueda de contenido que no queden menciones viejas en otros documentos (**H-15**).
- **Qué estudiar:** *trazabilidad regla → caso límite → criterio de aceptación → prueba*; *`INSERT … ON CONFLICT DO NOTHING`* y por qué evita la condición de carrera; *transacción única* (el reembolso automático y el cierre ocurren juntos o no ocurren); *por qué un valor derivado (el saldo no cobrado) no se guarda sino que se calcula*; *pruebas de integración con base real* (AC-CU-03-e).
- **Archivos tocados:** `decisiones.md`, `reglas-negocio.md`, `estados-y-transiciones.md`, `casos-de-uso.md`, `modelo-datos.md`, `actores-y-roles.md`, `catalogo-errores.md`, `casos-limite.md`, `criterios-aceptacion.md`, `advances.md`.

### 2026-10-11 — Paso 4 ✅ — Contrato 0.2.2 y `CHANGELOG.md`
- **Qué** (todo compatible; detalle en `docs/04-api/CHANGELOG.md`):
  - `openapi.yaml` 0.2.1 → **0.2.2** (`info.version` y ejemplo de `GET /version`).
  - D-142: el ejemplo `ADJUSTMENT_LIMIT_EXCEEDED` pasa a `limitPercent: 15`; `applyAdjustment` aclara que el límite es acumulado sobre cargos brutos.
  - D-143: `joinSession` documenta la regla del HOST, con un segundo ejemplo (`firstJoinerBecomesHost`); `ParticipantRole` tiene descripción.
  - D-144: `PublicTable.restaurantId` opcional (y en los 3 ejemplos); la descripción de `GET /menu` y del parámetro `restaurantId` se corrigió para coincidir con RN-TEN-03.
  - D-145: se quitaron las marcas "Propuesto" de `Participant.revoked` y `Order.rejection`.
  - D-147: `TIP_NOT_ALLOWED` queda en la **409** de `createPayment` y `registerCashPayment` (con ejemplo); la 422 genérica ya no dice `TIP_*`.
  - D-158: mínimo `min(minPaymentMinor, saldo)` en `createPayment`, `registerCashPayment` y `CreatePaymentRequest`; `PAYMENT_AMOUNT_INVALID` con `minMinor` ya calculado con el saldo (nuevo ejemplo 422).
  - D-159: `forceCloseSession` documenta sus efectos (conserva pagos y propinas; reembolso automático si el saldo queda negativo; saldo no cobrado si es positivo).
  - `getReceipt` con `x-permission: bill.read`.
  - H-08: `createOrderOnBehalf` dice "en nombre del cliente" (como D-113 y CU-31).
  - `CHANGELOG.md` reordenado (más reciente arriba), con encabezado de reglas (D-150) y entrada 0.2.2 fechada. Se quitó la nota obsoleta "Próximo paso: openapi.yaml v0.1" de 0.1.0 y la línea "proposed" de 0.2.1 quedó como historia, con la aclaración "aceptados en 0.2.2".
- **Por qué:** según `decisiones.md` §6 el contrato se toca al final, cuando las reglas y los documentos derivados ya están alineados (pasos 2 y 3). Un contrato que dice otra cosa que las reglas hace que Web y Android implementen mal. Todos los cambios son compatibles: ningún renombre, ningún campo obligatorio nuevo; el único campo nuevo es opcional.
- **Para confirmar por el dueño (interpretaciones mías):**
  1. **H-08, "etiqueta del pedido a nombre de la mesa":** lo interpreté como el `summary` de `createOrderOnBehalf` (decía "de la mesa"; D-113, CU-31 y el permiso dicen "del cliente"). Si te referías a otra cosa, avisame.
  2. **`getReceipt` → `bill.read`:** el catálogo de permisos no tiene uno específico para el comprobante; elegí el existente más cercano (el mismo que `getBill`, `listPayments` y `getOrder`). No se creó ningún permiso.
  3. **`limitMinor: 750000`** en el ejemplo del límite: subí de 500000 a 750000 para que siga siendo el 15 % de la misma base que antes era el 10 %. Ver H-19.
  4. **Fechas viejas:** el historial de Git empieza hoy (commit inicial), así que no hay forma de recuperar las fechas de 0.1.0, 0.2.0 y 0.2.1. Quedaron como "fecha no registrada" en vez de inventarlas.
  5. **`GET /menu`:** la descripción anterior daba prioridad a `restaurantId` sobre el token; RN-TEN-03 dice lo contrario. Se corrigió el contrato (no hay implementación, no rompe a nadie).
- **Hallazgos nuevos (no se resolvieron en silencio):** **H-16**, **H-17**, **H-18**, **H-19** (ver §3).
- **Qué NO se hizo:** no se validó el YAML con ningún programa (mis herramientas solo leen y escriben archivos; el lint es el Paso 5 y lo corre el dueño). Revisé a mano que las descripciones nuevas no tengan `: ` en texto sin comillas, que rompe el YAML. No se tocaron `endpoints.md` ni `eventos.md`.
- **Qué estudiar:** *cambio compatible vs. incompatible* (por qué un campo opcional nuevo no rompe y renombrar sí); *409 vs. 422* (conflicto de estado vs. dato que viola una regla); `x-permission` (es una *extensión* de OpenAPI: el lint no la entiende, es convención nuestra); `example` vs. `examples` (uno vs. varios ejemplos con nombre); *SemVer con `0.x`*; por qué un changelog se escribe de más reciente a más antiguo; las trampas del YAML (`: ` y `#` dentro de texto sin comillas).
- **Archivos tocados:** `docs/04-api/openapi.yaml`, `docs/04-api/CHANGELOG.md`, `docs/00-gobierno/advances.md`.
- **Commit sugerido:** `docs(api): contract 0.2.2`

### 2026-10-11 — Paso 5 ✅ — Redocly como única herramienta
- **Qué:**
  - `redocly.yaml` (H-09): la clave `no-server-example-com` **no existe** en Redocly; la regla real es `no-server-example.com` (con punto). **Corrección:** en una primera versión quité la regla creyendo que solo marcaba `example.com` y que `localhost` no la disparaba; era un error mío (leí la documentación a medias). El lint del dueño mostró 2 avisos de esta regla sobre los servidores `localhost`. Quedó apagada con el nombre correcto (`no-server-example.com: off`) y el comentario original, que era acertado. Apagadas: `info-license`, `no-server-example.com` y `operation-4xx-response`.
  - D-148: Spectral reemplazado por Redocly CLI en `ADR-0008` (decisión y consecuencias), `estrategia.md` (§6), `nube.md` (§4, `docs.yml`), `convenciones.md` (§7) y `devops.md` (responsabilidad 7).
- **Por qué:** una regla con nombre mal escrito en la configuración hace que el lint falle por la config y no por el contrato. Y dos herramientas que hacen lo mismo (Spectral y Redocly) obligan a mantener dos configuraciones.
- **Resultado del lint (dueño):** primer lint: contrato válido y 2 avisos de `no-server-example.com` (ya corregidos en la config). **Segundo lint: válido y sin avisos.** Esto también confirma que el YAML del Paso 4 es correcto.
- **Qué NO se hizo:** no puedo buscar texto dentro de los archivos, así que no sé si queda "Spectral" en otros documentos (README, `arquitecto.md`, roadmap…). **El dueño puede verificarlo con** `git grep -n -i spectral`. Tampoco toqué el encabezado de `convenciones.md` ("borrador v0.1… el contrato formal será openapi.yaml"), que está desactualizado: se suma al alcance de H-12 (Paso 8).
- **Qué estudiar:** *qué es un linter* y la diferencia entre error de configuración y hallazgo del contrato; *rulesets* (`extends: recommended`) y por qué se apaga una regla solo con motivo escrito; *DRY aplicado a herramientas* (una herramienta en vez de dos); por qué conviene verificar un nombre en la documentación oficial antes de "corregirlo" a ojo, y **correr la herramienta** antes de dar por buena una lectura de la documentación (este paso lo demuestra).
- **Archivos tocados:** `redocly.yaml`, `ADR-0008-contract-first-openapi.md`, `docs/06-pruebas/estrategia.md`, `docs/07-despliegue/nube.md`, `docs/04-api/convenciones.md`, `docs/08-agentes/devops.md`, `advances.md`.
- **Commit sugerido:** `chore(api): standardize on redocly`

### 2026-10-11 — Paso 6 ✅ — Catálogo de acciones de auditoría
- **Qué:** nuevo `docs/02-dominio/catalogo-auditoria.md`. Sección 2: **19 códigos** tomados textualmente de `estados-y-transiciones.md` y `reglas-negocio.md` (sesión, pedido, ítem, pago/reembolso, ajuste), con actor, `targetType`, datos y origen. Sección 3: **6 códigos propuestos 🟡** para lo que `RN-AUD-01` exige y nadie nombraba. Sección 4: lo que hoy **no** se audita.
- **Por qué:** sin vocabulario cerrado cada módulo inventa sus códigos y los reportes y pruebas se rompen. Los códigos nuevos van marcados 🟡 porque `AGENTS.md` prohíbe inventar sin avisar.
- **Hallazgos nuevos:** **H-20** y **H-21** (ver §3).
- **Qué estudiar:** *registro de auditoría append-only* y por qué se escribe en la misma transacción; *vocabulario controlado / enum cerrado*; qué va en un `beforeSummary` (resumen mínimo, nunca la entidad completa ni secretos).
- **Archivos tocados:** `docs/02-dominio/catalogo-auditoria.md` (nuevo), `advances.md`.
- **Commit sugerido:** `docs(domain): add audit action catalog`

### 2026-10-11 — Paso 7 ✅ — Duplicados de la raíz eliminados
- **Resultado (dueño):** `Get-FileHash` dio `True` para `roadmap-fases.md` y `cambios-v0.2.md` (idénticos a los de `docs/`). `decisiones.md` de la raíz ya era una versión vieja. Se borraron las tres con `git rm`; H-10 cerrado.
- **Qué se verificó** (tamaños, sin leer contenido): `decisiones.md` de la raíz pesa 9,49 KB y el oficial 13,33 KB → **la copia de la raíz está vieja**. `roadmap-fases.md` tiene **15839 bytes en ambos** lugares y `cambios-v0.2.md` 1,60 KB en ambos → probablemente idénticos, **a confirmar con hash**. `README.md` de la raíz es el README principal del repo: no es un duplicado (no hay copia en `docs/00-gobierno` ni en `docs/08-agentes`).
- **Acción del dueño:** comparar hashes y, si coinciden (o si la raíz es la vieja), borrar las tres copias de la raíz con `git rm`. El Paso 7 pasa a ✅ cuando estén borradas.
- **Qué estudiar:** *hash de archivo* (`Get-FileHash`) para comparar contenido sin leerlo; por qué igual tamaño **no** prueba igual contenido.

### 2026-10-11 — Paso 8 🔄 (parcial) — H-12 resuelto; la consolidación queda para antes de la Fase 2
- **Qué:** H-12 corregido: `AGENTS.md` §1 punto 3 (el contrato ya existe y manda sobre `endpoints.md`), encabezado de `convenciones.md` (ya no dice "borrador v0.1"), y `README.md` (ahora cita D-101…D-159, Q-01…Q-20 resueltas, descripción de `04-api/`).
- **Qué NO se hizo:** la consolidación de las secciones "v0.2" en el cuerpo de cada documento (D-157). D-157 la ubica **antes de la Fase 2**, no de la Fase 1, y la Fase 1 (dominio puro) no depende de ella. Se hace antes de WP-BE-20. **H-15 cerrado** (el dueño corrió `git grep`): el único documento desactualizado era `cambios-v0.2.md` (Q-15 y la lista de preguntas); se marcó como documento histórico y se corrigió la fila de hosting. El "10 %" de `endpoints.md` y `openapi.yaml` es el ejemplo de `percentBp: 1000`, no el límite del Mozo; el `HOST` de ADR-0003 es consistente con D-143.
- **Archivos tocados:** `AGENTS.md`, `README.md`, `docs/04-api/convenciones.md`, `docs/00-gobierno/cambios-v0.2.md`, `advances.md`.
- **Commit sugerido:** `docs: update stale references to the contract (H-12)`

### 2026-10-11 — Paso 9 🔄 — Arranque del backend (WP-BE-10, primera tanda)
- **Versiones verificadas en la web (no de memoria):** JDK 25.0.2 (ya instalado por el dueño; Docker 29.8.2 y Git 2.54.0 también); **Gradle 9.8.1** (versión actual en `services.gradle.org`; Gradle 9.1+ ejecuta sobre Java 25); **Kotlin 2.4.0** (estable del 3 de junio de 2026; solo Kotlin 2.3+ puede generar bytecode para Java 25); **JUnit 6.1.2** (BOM). Spring Boot **no** se agrega todavía: es del WP-BE-20 (Fase 2).
- **Archivos a tocar (nuevos, en `backend/`):** `settings.gradle.kts`, `build.gradle.kts`, `shared/domain/DomainException.kt`, `orders/domain/ItemStatus.kt`, `orders/domain/OrderItem.kt`, `orders/domain/OrderStatus.kt`, y sus pruebas `ItemTestSupport.kt` (apoyo compartido), `ItemTransitionsTest.kt` y `OrderStatusDerivationTest.kt` (unas 67 pruebas). Después se generaron `gradlew`, `gradlew.bat` y `gradle/wrapper/` con `gradle wrapper --gradle-version 9.8.1` (los corrió el dueño).
- **Hallazgo H-22 (a confirmar, dueño):** si un ítem pasa directo de `QUEUED` a `READY` y luego se deshace, vuelve a `IN_PREPARATION` con `startedAt` vacío; `estados-y-transiciones.md` no dice qué hacer en ese caso. Se dejó sin completar para no inventar.
- **Qué estudiar:** *objeto inmutable* (por qué un error no puede dejar datos a medias); *reloj inyectado* (`now` por parámetro) para probar sin esperar; diferencia entre probar ejemplos y recorrer todos los estados alcanzables (BFS) como "prueba de propiedades" sin librerías extra.
- **Resultado (dueño, `.\gradlew.bat test`):** `BUILD SUCCESSFUL`. Reportes de Gradle: `ItemTransitionsTest` **49** pruebas y `OrderStatusDerivationTest` **18**, **0 fallos, 0 errores, 0 omitidas** (67 en total). `.gitignore` ya cubre `backend/.gradle/`, `backend/build/` y `backend/.kotlin/`.
- **Estado de esta tanda:** ✅ (compila y pasa sobre JDK 25 + Kotlin 2.4.0 + Gradle 9.8.1). El **Paso 9 sigue 🔄**: WP-BE-10 todavía no está completo.
- **Falta de WP-BE-10 (próximas tandas):** `Money` y cargos; agregado `Order` (aceptar, rechazar, cancelar, expirar, exclusiones, usa la derivación); `TableSession` y su cierre; `Payment`/`Bill` (saldo, propina, ajustes); `JoinCode`. Después: tokens de diseño (WP-UX-10, Q-21).
- **Commits sugeridos:** `chore(backend): add gradle build with kotlin and junit` y `feat(backend): add item transitions and order status derivation (WP-BE-10)`.

### 2026-10-12 — Paso 9 🔄 — WP-BE-10, tanda 2: dinero y cuenta (✅ verificada)
- **Qué:** `Money` (centavos, no negativo, aritmética exacta, porcentaje en puntos básicos con redondeo half-up, RN-ADJ-03); `Balance` (saldo con signo); cuenta derivada (`ChargeLine.isBillable`, `BillableCharges`, `BillCalculator` → `BillSummary`: cargos, ajustes, pagado neto, propinas, saldo); y `PaymentRules` (RN-BIL-03, RN-TIP-01/03/04, D-146, D-158) con los datos extra del catálogo (`minMinor`, `balanceMinor`, `maxTipMinor`). Seis códigos nuevos en `ErrorCode`, todos del catálogo.
- **Por qué:** "una sola definición" de cada suma (regla de oro 3). La cuenta se deriva y no se guarda, así no puede desincronizarse. Los topes de propina se comparan con enteros exactos (sin elegir un redondeo que ningún documento define).
- **Archivos (en `backend/src`):** `main/.../shared/domain/Money.kt`, `shared/domain/DomainException.kt` (ampliado), `billing/domain/BillingStatuses.kt`, `billing/domain/Bill.kt`, `billing/domain/PaymentRules.kt`; pruebas `test/.../shared/domain/MoneyTest.kt`, `billing/domain/BillCalculatorTest.kt`, `billing/domain/PaymentRulesTest.kt` (unas 90 nuevas; total esperado ~157).
- **Pruebas destacadas:** el redondeo se compara contra `BigDecimal` (30.000 casos); la validación del monto contra una regla independiente (unas 68.000 combinaciones); el ejemplo numérico del contrato (`1.450.000 − 145.000 − 500.000 = 805.000`).
- **Hallazgo nuevo:** **H-23** (prioridad de errores en un pago). `billing` usa los enums de `orders`: la dirección de dependencia se confirma con ArchUnit en el WP-BE-20.
- **Qué estudiar:** *value class* y por qué dinero = `Long` de centavos (no `Double`); *puntos básicos* y redondeo half-up con enteros; *prueba contra un oráculo* (comparar con una regla independiente); por qué la cuenta se deriva en vez de guardarse.
- **Estado:** ✅ verificada leyendo los reportes XML de `backend/build/test-results/test/` (corrida del 2026-10-12): `MoneyTest` 13, `BillCalculatorTest` 62, `PaymentRulesTest` 15 = **90** pruebas, 0 fallos, 0 errores, 0 omitidas. La estimación "unas 90 / ~157 en total" de arriba era aproximada; los números reales son estos y los de la tanda 3.
- **Commit sugerido:** `feat(backend): add money, bill calculation and payment rules (WP-BE-10)`

### 2026-10-12 — Paso 9 🔄 — WP-BE-10, tanda 3: ajustes, reembolsos y anulaciones (✅ verificada)
- **Revisión de la tanda 2:** se contrastó contra su descripción y contra RN-BIL/RN-TIP/catálogo de errores: está completa. Lo que faltaba del plan original (ítem 5: "Payment, Bill y ajustes") no estaba en ninguna tanda; se hace acá.
- **Qué:** `AdjustmentRules` (importe del descuento %, monto, manual y cortesía; `ADJUSTMENT_INVALID`, `ADJUSTMENT_EXCEEDS_BALANCE`, límite ACUMULADO del Mozo `ADJUSTMENT_LIMIT_EXCEEDED`, tope absoluto opcional); `RefundRules` (`REFUND_EXCEEDS_PAID` y los efectos monetarios del cierre forzado: reembolso automático si saldo < 0, "no cobrado" si saldo > 0, D-159); `VoidRules` (anular pago y anular ajuste con ventana de 10 min y "solo los propios"). `ErrorCode` suma 7 códigos, todos del catálogo: `ADJUSTMENT_INVALID`, `ADJUSTMENT_EXCEEDS_BALANCE`, `ADJUSTMENT_LIMIT_EXCEEDED`, `ADJUSTMENT_NOT_VOIDABLE`, `PAYMENT_NOT_VOIDABLE`, `REFUND_EXCEEDS_PAID`, `FORBIDDEN`.
- **Por qué:** son las reglas de dinero que quedaban por cubrir antes de armar `TableSession` (cuyo cierre usa saldo, reembolso automático y "no cobrado"). Siguen siendo funciones puras: el contexto (saldo, sumas, quién es el Mozo) lo arma la capa de aplicación bajo lock.
- **Archivos (en `backend/src`):** `main/.../billing/domain/AdjustmentRules.kt`, `RefundAndVoidRules.kt`, `shared/domain/DomainException.kt` (ampliado); pruebas `test/.../billing/domain/AdjustmentRulesTest.kt` y `RefundAndVoidRulesTest.kt` (unas 30 pruebas nuevas, incluida una contra un "oráculo" de 201 montos).
- **Hallazgos nuevos:** **H-24** (prioridad de errores en un ajuste: se eligió inválido → saldo → límite; el catálogo no la fija). **H-25** (`ADJUSTMENT_ITEM_INVALID` de la cortesía, RN-ADJ-04: falta el snapshot completo de `OrderItem` con su id; queda para la tanda del agregado `Order`, que lo trae). Sobre **H-19**: `limitMinor` se informa como el tope efectivo en centavos (15 % de los cargos brutos, o el tope absoluto si es menor); sigue pendiente que el dueño lo confirme.
- **Documentación corregida de paso:** `build.gradle.kts` citaba D-149 para "un solo módulo" (es ADR-0002); `entorno-local.md` y `roadmap-fases.md` ya no nombran Spectral (D-148); `catalogo-auditoria.md` dice `audit_event` como en `modelo-datos.md`.
- **Qué estudiar:** *límite acumulado vs. por operación*; *`sealed`/datos incoherentes como error de validación* (`AdjustmentRequest`); *entero exacto vs. redondeo* en un tope; por qué el "no cobrado" es un valor derivado y no se guarda.
- **Falta de WP-BE-10:** agregado `Order` (aceptar, rechazar, cancelar, expirar, exclusiones; trae H-25), `TableSession` y su cierre (`SESSION_BALANCE_PENDING`, etc.), `JoinCode`. Después: tokens de diseño (WP-UX-10, Q-21; el SVG de marca es un PNG incrustado, ver Q-21).
- **Estado:** ✅ verificada con los mismos reportes XML: `AdjustmentRulesTest` 14 y `RefundAndVoidRulesTest` 11 = **25** pruebas (no "unas 30"). **Total del backend: 182** (67 de la tanda 1 + 90 + 25), **0 fallos, 0 errores, 0 omitidas** (14+62+15+11+49+18+13). Commits locales verificados en el registro de Git: `aecfd66` (reglas de dinero, facturación, pagos, ajustes, reembolsos y anulaciones) y `0b1b959` (documentación); el push a `origin` lo confirmó el dueño (no es verificable desde mis herramientas). **El Paso 9 sigue 🔄:** WP-BE-10 no está terminado.
- **Commit sugerido:** `feat(backend): add adjustment, refund and void rules (WP-BE-10)`

### 2026-10-12 — Paso 9 🔄 — WP-BE-10, tanda 4: agregado `Order` (✅ verificada)
- **Revisión previa:** tandas 2 y 3 verificadas y pasadas a ✅ (ver arriba). Estado de Git: commits `aecfd66` y `0b1b959`.
- **Qué ya existe** (no se vuelve a escribir): `OrderItem` con las transiciones de ítem (`queue`, `exclude`, `start`, `markReady`, `deliver`, `cancel`, `undo`); `OrderStatusDerivation.fromItems` (estados §4); enums `OrderStatus`/`ItemStatus`; `Money`; `ChargeLine.isBillable`; `DomainException`/`ErrorCode`.
- **Reglas que se implementan:** RN-ORD-05 (nace `PENDING_REVIEW` con ítems `PENDING`), RN-ORD-08 (gana la primera decisión), RN-ORD-09 (rechazo con `reasonCode` ∈ {`OUT_OF_STOCK`, `KITCHEN_CLOSED`, `ITEM_UNAVAILABLE`, `OTHER`} + texto ≤ 140), RN-ORD-10 (aceptar con exclusiones; si se excluyen todos → `ORDER_ALL_ITEMS_EXCLUDED`), RN-ORD-11 (cancelar solo en `PENDING_REVIEW`), RN-ORD-12 (expirar), RN-ORD-13 (tras aceptar no se modifican cantidades ni precios), RN-ORD-18 (pedido del personal nace `ACCEPTED`), RN-ORD-04 (snapshot del ítem).
- **Diseño:**
  1. `ItemSnapshot` (nuevo): `productId`, `productName`, `productNameI18n`, `stationId`, `quantity ≥ 1`, `note?`, `basePrice`, `options: List<OptionSnapshot>` (grupo, opción y `priceDelta`). **Deriva** (una sola definición) `optionsDelta = Σ priceDelta`, `unitPrice = base + optionsDelta` (RN-OPT-04) y `lineTotal = unitPrice × quantity` (RN-BIL-01). Inmutable.
  2. `OrderItem` (se modifica): pasa a llevar el `ItemSnapshot`; `pending(id, snapshot)`. Las transiciones no cambian. Expone `lineTotal`.
  3. `Order` (nuevo, inmutable, el reloj entra por parámetro): `create(...)` → `PENDING_REVIEW`; `createAccepted(...)` (RN-ORD-18, reutiliza `create` + `accept`); `accept(now, excludedItemIds)`; `reject(now, reason, text?)`; `cancel(now)`; `expire(now, ttl)`. Aceptar excluye con `OrderItem.exclude` y encola el resto con `queue`; el estado resultante **se deriva con `OrderStatusDerivation`** (no se reescribe la lógica). Rechazar, cancelar y expirar pasan los ítems `PENDING → CANCELLED` (estados §3) reutilizando `exclude`. Guarda `acceptedAt` y `decidedAt`. Un error no modifica nada.
  4. **H-25:** nuevo `billing/domain/CourtesyRules` que, con el ítem (id, estado, `lineTotal`) y los ítems que ya tienen cortesía, lanza `ADJUSTMENT_ITEM_INVALID` (cancelado o ya con cortesía, RN-ADJ-04) o devuelve el `lineTotal` para `AdjustmentRequest.itemLineTotal`. **Aclaración:** H-25 trata de la cortesía (`ADJUSTMENT_ITEM_INVALID`), no de las exclusiones; se resuelve acá porque esta tanda trae el snapshot con id y precio. Las exclusiones son RN-ORD-10.
  5. `ErrorCode` suma 7 códigos, todos del catálogo: `ORDER_EMPTY`, `ORDER_ALREADY_DECIDED`, `ORDER_NOT_CANCELLABLE`, `ORDER_EXPIRED`, `ORDER_ALL_ITEMS_EXCLUDED`, `ITEM_NOT_FOUND` (id a excluir que no pertenece al pedido), `ADJUSTMENT_ITEM_INVALID`.
- **Decisiones de diseño (a confirmar):** (a) accept/reject sobre un pedido ya decidido → `ORDER_ALREADY_DECIDED`; cancelar fuera de `PENDING_REVIEW` → `ORDER_NOT_CANCELLABLE` (así lo dice el catálogo); (b) H-26 y H-27 (arriba); (c) el motivo de exclusión y de cancelación lo valida y audita la capa de aplicación (igual que `OrderItem.cancel`); (d) `decidedBy` de `ORDER_ALREADY_DECIDED` es un nombre: el dominio informa `decidedAt` y `currentStatus`, y la capa de aplicación agrega el nombre; (e) las validaciones con configuración (límite de líneas, cantidades, 10 opciones) siguen siendo de la capa de aplicación (RN-ORD-02, columna "A").
- **Archivos a tocar** (en `backend/src`): `main/.../orders/domain/ItemSnapshot.kt` (nuevo), `OrderItem.kt` (modifica), `Order.kt` (nuevo), `billing/domain/CourtesyRules.kt` (nuevo), `shared/domain/DomainException.kt` (+7 códigos), KDoc de `billing/domain/AdjustmentRules.kt`; pruebas: `test/.../orders/domain/ItemSnapshotTest.kt`, `OrderTest.kt`, `ItemTestSupport.kt` (ajuste para el snapshot), `billing/domain/CourtesyRulesTest.kt`; y este `advances.md`.
- **Pruebas previstas (~40):** snapshot (total con 0, 1 y varias opciones, cantidad inválida, inmutabilidad); crear (estado e ítems `PENDING`, vacío → `ORDER_EMPTY`); aceptar (todos encolados, estado `ACCEPTED`, `acceptedAt`, con exclusiones, excluir todos, id ajeno → `ITEM_NOT_FOUND`); rechazar (cada `reasonCode`, texto de 140 y 141 caracteres, ítems cancelados); cancelar y expirar (válidos, TTL justo antes/después del límite); **matriz de transiciones inválidas**: cada acción desde cada estado no permitido lanza el código esperado y deja el pedido igual; pedido del personal nace `ACCEPTED`; cortesía (válida, ítem cancelado, cortesía repetida).
- **Queda para la tanda siguiente:** delegar las acciones de ítem en `Order` y recalcular el estado en la misma operación (RN-ORD-14); cancelación por cierre forzado (`SESSION_FORCED`), que se verá con `TableSession`.
- **Qué estudiar (se completa al terminar):** *agregado* (DDD) y por qué solo se modifica por su raíz; *objeto de valor / snapshot*; *derivar vs. guardar*; *matriz de transiciones inválidas*.
- **Estado:** ✅ verificada (resultados reales abajo; el diseño se implementó con las interpretaciones propuestas de H-26 y H-27, que siguen **a confirmar**: no hay constancia de aprobación del dueño). **Lo que se escribió:** `ItemSnapshot.kt` (con `OptionSnapshot`), `Order.kt` (con `Rejection` y `RejectionReason`), `CourtesyRules.kt`; `OrderItem.kt` ahora lleva el snapshot (`pending(id, snapshot)`, `lineTotal`); `ErrorCode` suma los 7 códigos; KDoc de `AdjustmentRules.kt`. Pruebas: `ItemSnapshotTest` (7), `OrderTest` (33, de ellas 16 de la matriz de transiciones inválidas), `CourtesyRulesTest` (5) = **45 nuevas; total esperado 227** (182 + 45; es una cuenta, no un resultado). Para no tocar las 49 pruebas de `ItemTransitionsTest`, `ItemTestSupport.kt` define `OrderItem.pending(id)` como extensión de prueba. **Las pruebas las corrió el dueño** (mis herramientas no ejecutan Gradle): primero las específicas y después la suite completa; ambas dieron `BUILD SUCCESSFUL`.
- **Commit sugerido:** `feat(backend): add order aggregate, item snapshot and courtesy rules (WP-BE-10)`
- **Resultado verificado (2026-10-12, reportes XML de `backend/build/test-results/test/`, una sola corrida a las 11:16:11 UTC; el dueño confirmó `BUILD SUCCESSFUL` en las pruebas específicas y en la suite completa):** `AdjustmentRulesTest` 14, `BillCalculatorTest` 62, `CourtesyRulesTest` **5**, `ItemSnapshotTest` **7**, `ItemTransitionsTest` 49, `MoneyTest` 13, `OrderStatusDerivationTest` 18, `OrderTest` **33**, `PaymentRulesTest` 15, `RefundAndVoidRulesTest` 11. **Total real: 227 pruebas (10 clases), 0 fallos, 0 errores, 0 omitidas.** Las 45 nuevas (5+7+33) están incluidas y las 182 anteriores siguen pasando. La cifra coincide con la estimada, pero se confirmó sumando los reportes.
- **Hallazgos de esta tanda:** H-25 resuelto en código (`CourtesyRules`); H-26 y H-27 (interpretaciones propuestas, a confirmar); H-28 (TTL con `>=`) y H-29 (cortesía sobre ítem de pedido no aceptado), nuevos.
- **Qué estudiar:** *agregado* (DDD): `Order` es la raíz y los ítems no se modifican por fuera de ella; *objeto de valor y snapshot* (`ItemSnapshot`: sin identidad, inmutable, copia del catálogo al momento del pedido); *derivar en vez de guardar* (`lineTotal` y el estado tras aceptar); *reutilizar en vez de duplicar* (`OrderStatusDerivation`, `OrderItem.exclude`); *matriz de transiciones inválidas* (cada estado × cada acción con el código esperado); *error de negocio vs. error de programación* (`DomainException` vs. `IllegalArgumentException`); *función de extensión sobre un `companion object`* (el atajo `OrderItem.pending(id)` de las pruebas).
- **Falta de WP-BE-10:** delegar acciones de ítem en `Order` y recalcular su estado (RN-ORD-14); `TableSession` y su cierre; `JoinCode`. Después WP-UX-10 (Q-21). El **Paso 9 sigue 🔄**.

### 2026-10-12 — Paso 9 🔄 — WP-BE-10: corrección de H-29 (cortesía sobre ítem `PENDING`) — ✅ verificada en código y regla; contrato pendiente
- **Verificación previa (independiente):** se leyeron los 10 reportes XML (todos de la misma corrida, 11:16:11 UTC): 227 pruebas, 0 fallos, 0 errores, 0 omitidas; y el código de `Order`, `OrderItem`, `ItemSnapshot` y `CourtesyRules`. Git (salida del dueño, antes de este cambio): `main...origin/main`; modificados `AdjustmentRules.kt`, `OrderItem.kt`, `DomainException.kt`, `ItemTestSupport.kt` y `advances.md`; sin seguimiento `CourtesyRules.kt`, `ItemSnapshot.kt`, `Order.kt` y sus tres pruebas.
- **Qué:** (1) regla: RN-ADJ-04 exige un ítem **aceptado** (no `PENDING`), con fila nueva en el historial de `reglas-negocio.md`; (2) código: `CourtesyRules.lineTotalFor` rechaza `PENDING` con `ADJUSTMENT_ITEM_INVALID`; (3) pruebas en `CourtesyRulesTest`: `a pending item of an order not yet accepted is ADJUSTMENT_ITEM_INVALID (H-29)` y `every accepted state can receive a courtesy and only pending and cancelled cannot`. Pasan de 5 a 7; total **229 (verificado, ver "Estado de H-29")**.
- **Por qué:** RN-BIL-01 solo cobra ítems de pedidos aceptados. Una cortesía sobre un ítem `PENDING` restaría plata de OTROS ítems que sí se cobran, y si el pedido luego se rechaza o vence la cortesía quedaría huérfana (RN-ADJ-08 solo anula cortesías al cancelar un ítem aceptado). Basta mirar el estado del ítem porque un ítem `PENDING` solo existe mientras su pedido está `PENDING_REVIEW`.
- **Estado de H-29:** ✅ verificado en código y regla; **abierto en la documentación del contrato** (tres referencias, ver "Pendiente / no tocado") hasta que el dueño apruebe la propuesta de cambios. **Resultado verificado (2026-10-12, reportes XML de `backend/build/test-results/test/`, una sola corrida a las 11:35:05 UTC; el dueño confirmó `BUILD SUCCESSFUL` en `CourtesyRulesTest` y en la suite completa):** `AdjustmentRulesTest` 14, `BillCalculatorTest` 62, `CourtesyRulesTest` **7**, `ItemSnapshotTest` 7, `ItemTransitionsTest` 49, `MoneyTest` 13, `OrderStatusDerivationTest` 18, `OrderTest` 33, `PaymentRulesTest` 15, `RefundAndVoidRulesTest` 11. **Total real: 229 pruebas (10 clases), 0 fallos, 0 errores, 0 omitidas.** Las 2 nuevas están en `CourtesyRulesTest`: la del ítem `PENDING` (con `itemId` en el error) y la que recorre los 6 estados (4 permitidos, `PENDING` y `CANCELLED` prohibidos). No hay prueba de un ítem `PENDING` que además tenga cortesía previa (daría el mismo código). El Paso 9 sigue 🔄: WP-BE-10 no está terminado (H-30, `TableSession`, `JoinCode`).
- **Pendiente / no tocado:** `catalogo-errores.md` (fila de `ADJUSTMENT_ITEM_INVALID`), `casos-limite.md` (CL-194) y la descripción del error en `openapi.yaml` todavía dicen "cancelado o ya con cortesía". El contrato solo se toca junto con `CHANGELOG.md` (Paso 4 · regla 5). Es una aclaración compatible, a hacer cuando se apruebe.
- **Hallazgos:** H-30 nuevo (hueco de cobertura de la matriz de transiciones de `Order`). H-23 se reubicó en orden numérico. H-26 y H-27 siguen **a confirmar** (cuerpo y tabla ya no se contradicen).
- **Qué estudiar:** *invariante de dominio* ("ítem `PENDING` ⇔ pedido `PENDING_REVIEW`": por qué alcanza mirar el ítem); *prueba por clases de equivalencia* (los 6 estados de ítem divididos en permitidos y prohibidos); por qué la regla se corrige **antes** que el código; *huérfano* (dato que apunta a algo que dejó de contar).
- **Archivos tocados:** `backend/.../billing/domain/CourtesyRules.kt`, `backend/.../billing/domain/CourtesyRulesTest.kt`, `docs/02-dominio/reglas-negocio.md`, `docs/00-gobierno/advances.md`.
- **Commit sugerido (no ejecutado):** `fix(backend): reject courtesy on pending items (H-29)`

### 2026-10-12 — Paso 9 🔄 — H-29: documentación y contrato 0.2.3 alineados (lint de 0.2.3 pendiente)
- **Qué:** `catalogo-errores.md` (fila de `ADJUSTMENT_ITEM_INVALID`), `casos-limite.md` (CL-194) y `estados-y-transiciones.md` §8.2 (guarda de cortesía) incluyen ítems `PENDING`; `openapi.yaml` pasa a **0.2.3** (`info.version`, descripción de `applyAdjustment`, ejemplo de `GET /version`); `CHANGELOG.md` suma `[0.2.3]`. Las filas vigentes de 1.1 apuntan a 0.2.3.
- **Por qué:** D-150 (la versión del YAML coincide con la primera entrada del changelog) y `decisiones.md` §6 (primero reglas y documentos derivados, al final el contrato). Cambio compatible: solo aclara una descripción.
- **No tocado a propósito:** la historia del Paso 4 y las bitácoras del 11 de octubre que citan 0.2.2; `casos-de-uso.md` (CU-37 solo lista el código).
- **Sin verificar:** el lint de Redocly sobre 0.2.3 (mis herramientas no lo ejecutan). H-26 y H-27 siguen a confirmar; H-30 pendiente; el Paso 9 sigue 🔄.
- **Qué estudiar:** *propagación de un cambio de regla* (regla → documentos derivados → contrato) y *versionado de un contrato* (por qué un cambio compatible igual lleva versión y entrada de changelog).
- **Archivos tocados:** `catalogo-errores.md`, `casos-limite.md`, `estados-y-transiciones.md`, `openapi.yaml`, `CHANGELOG.md`, `advances.md`.
- **Commit sugerido (no ejecutado):** `docs(api): contract 0.2.3, courtesy only on accepted items (H-29)`

### 2026-10-12 — Paso 9 ✅ — WP-BE-10 completado
- **Qué:** `Order` delega las transiciones de sus ítems y deriva el estado después de cada acción; se añadieron `Payment` y su conexión con el cálculo de `Bill`; se implementaron `TableSession` y `JoinCode`; se midió JaCoCo y se hizo cumplir el mínimo de cobertura en `check`. H-26/H-27/H-28 quedaron reflejadas en dominio y documentación, y se actualizó el contrato a 0.2.4 con su changelog.
- **Por qué:** completa el alcance de dominio puro de WP-BE-10 sin agregar infraestructura ni duplicar reglas existentes; `OrderItem`, `OrderStatusDerivation`, `PaymentRules`, `RefundRules` y `VoidRules` siguen siendo las fuentes únicas para sus reglas.
- **Qué estudiar:** delegación desde la raíz de un agregado; inmutabilidad y transiciones por copia; proyección de una entidad a una consulta derivada; diferencia entre errores de dominio y precondiciones de programación; cobertura de líneas como métrica medida.
- **Verificación inicial (antes de las correcciones finales):** `.\backend\gradlew.bat -p backend check jacocoTestReport` → `BUILD SUCCESSFUL`; 273 pruebas, 0 fallos/errores/omitidas; 631/642 líneas cubiertas (**98,29 %**, umbral 90 %). La CLI de Redocly no estaba disponible y el lint quedó pendiente.
- **Interpretación registrada:** `TableSession` conserva el vencimiento inclusivo `now >= lastActivityAt + idleTtl`; que el TTL de inactividad venza exactamente en el límite es una interpretación técnica pendiente de confirmación del producto, no una decisión confirmada.
- **Siguiente paso:** WP-UX-10 sigue pendiente por Q-21; WP-BE-20/WP-BE-40 incorporarán aplicación, persistencia y actualización condicional del job sin cambiar el dominio puro.
- **Archivos:** `backend/src/main/kotlin/com/somphie/`, pruebas del dominio, `backend/build.gradle.kts`, `docs/02-dominio/` y `docs/04-api/`.
- **Commit sugerido (no ejecutado):** `feat(backend): complete pure domain for WP-BE-10`

### 2026-10-12 — Correcciones finales de WP-BE-10 ✅
- **Qué:** `billing.domain` dejó de depender de `orders.domain`; `Order` expone los importes que califican como facturables y `CourtesyRules` recibe una proyección local. OpenAPI y changelog 0.2.5 alinean la revocación del HOST con RN-ACC-10; `TableSession` tiene pruebas de copia y exposición inmutable; `Payment` y el cierre forzado explican dónde se conservan los motivos.
- **Por qué:** elimina la dependencia entre dominios sin duplicar el filtro de cargos y conserva fuera del agregado los motivos que deben quedar en auditoría.
- **Criterios conservados:** el TTL inclusivo de sesiones sigue siendo una interpretación técnica pendiente de confirmación de producto; H-26/H-27/H-28 continúan como decisiones confirmadas.
- **Verificación final:** `.\backend\gradlew.bat -p backend clean check jacocoTestReport` → `BUILD SUCCESSFUL`; 235 pruebas, 0 fallos, errores u omitidas; 653/669 líneas cubiertas (**97,61 %**, umbral 90 %). `git diff --check` pasó. Redocly no está disponible; el lint del contrato 0.2.5 queda pendiente.
- **Siguiente paso:** queda pendiente la revisión manual; no se hicieron commits ni pushes.
- **Archivos:** `Bill.kt`, `CourtesyRules.kt`, `Payment.kt`, `Order.kt`, `TableSession.kt`, pruebas de billing/orders/floor, `backend-arquitectura.md`, `openapi.yaml`, `CHANGELOG.md` y esta bitácora.

### 2026-10-12 — WP-OPS-20 ✅
- **Qué:** se añadieron Compose para PostgreSQL 16.15, Mailpit 1.31.5 y Prism 5.15.10, `.env.example` comentado, instrucciones actuales para PowerShell/Bash y plantillas de PR, defecto y paquete de trabajo.
- **Por qué:** es requisito previo documentado para el esqueleto funcional del backend; no se inicia CI ni otro paquete.
- **Archivos:** `infra/docker-compose.yml`, `infra/.env.example`, `README.md`, `docs/07-despliegue/entorno-local.md`, `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/bug_report.md`, `.github/ISSUE_TEMPLATE/work_package.md` y esta bitácora. `.gitattributes` y `.gitignore` ya cumplían el objetivo y no se modificaron.
- **Qué estudiar:** servicios locales aislados en contenedores y montaje de un contrato OpenAPI de solo lectura.
- **Validación desde clon limpio de Windows:** se clonó el commit local en una carpeta temporal y se copiaron únicamente los archivos WP-OPS-20 pendientes de Git para probar la versión actual; no se copió ningún `.env` real. Con un `COMPOSE_PROJECT_NAME` aislado se ejecutaron `docker compose config` y `docker compose up -d`; PostgreSQL y Mailpit quedaron `healthy`, Prism quedó ejecutándose, `pg_isready` confirmó conexiones y Mailpit/Prism respondieron HTTP 2xx.
- **Limpieza y alcance:** `docker compose down` retiró contenedores/red; se eliminó solo el volumen temporal aislado y la carpeta del clon. Se verificó que el volumen original `somphie-local_postgres-data` siguiera existiendo. `git diff --check` pasó e `infra/.env` está ignorado. No se hicieron commits ni pushes. Redocly y CI siguen fuera del alcance de WP-OPS-20.
- **Siguiente paso:** WP-OPS-20 queda completo y su dependencia para WP-BE-20 está satisfecha; WP-OPS-21 permanece pendiente.

### 2026-10-12 — WP-OPS-21 🔄
- **Qué:** se agregaron workflows de backend, documentación, seguridad, Web y Android. Las acciones/herramientas usan versiones fijadas, Gradle cachea dependencias y gitleaks escanea todo el historial con checkout completo.
- **Por qué:** WP-OPS-20 ya está validado; esta tarea agrega puertas automatizadas sin adelantar WP-BE-20, WP-WEB-20 ni WP-AND-20.
- **Archivos:** `.github/workflows/{backend,docs,security,web,android}.yml`, `.markdownlint-cli2.yaml`, `.gitleaksignore` y esta bitácora. Se exceptúan únicamente 10 fingerprints históricos de ejemplos sintéticos `guestToken` del OpenAPI; se verificó cada línea y cualquier hallazgo distinto sigue bloqueando el escaneo.
- **Qué estudiar:** filtros de rutas y permisos mínimos en GitHub Actions, caché de Gradle, lint de Markdown/OpenAPI y escaneo de historial con gitleaks.
- **Validación inicial de WP-OPS-21:** `.\backend\gradlew.bat -p backend check` → `BUILD SUCCESSFUL`; Markdown → 60 archivos, 0 incidencias; Redocly → contrato válido; lychee 0.24.2 → 60 archivos, 0 enlaces Markdown detectados; Gitleaks 8.30.1 → 21 commits sin hallazgos no exceptuados; actionlint 1.7.12 validó los cinco workflows; `git diff --check` pasó.
- **Validación de estas correcciones:** `.\backend\gradlew.bat -p backend check` → `BUILD SUCCESSFUL` (tareas sin cambios, actualizadas); `npx --yes markdownlint-cli2@0.23.3 "**/*.md"` → 60 archivos, 0 incidencias; `npx --yes @redocly/cli@2.62.1 lint docs/04-api/openapi.yaml` → contrato válido; Gitleaks 8.30.1 → 22 commits escaneados, sin hallazgos; `git diff --check` pasó. `actionlint` no está instalado, por lo que no se repitió tras las modificaciones.
- **Web/Android:** cada workflow mantiene un único job con nombre estable. Si existe la aplicación, ejecuta validaciones reales; si falta, termina en fallo con estado “validación no ejecutada”, y si fallan los comandos el job también falla tras ejecutarlos. Ese job puede configurarse como check requerido y bloqueará merges mientras la app no exista; no se inspeccionó ni modificó la protección remota de ramas. `WP-WEB-20` y `WP-AND-20` habilitarán las tareas reales.
- **Auditoría de dependencias:** `nube.md` §4 describe un escaneo informativo de dependencias, pero el alcance aprobado de WP-OPS-21 en `roadmap-fases.md` incluye Gitleaks y no esa auditoría. No se agregó una herramienta; queda registrada la discrepancia para asignarla a un paquete futuro si corresponde.
- **Limitaciones:** Actions no puede ejecutarse ni confirmarse antes de subir los cambios; por eso el paquete sigue abierto hasta que los workflows corran en GitHub. El link checker no encontró enlaces Markdown que probar y Web/Android todavía no tienen aplicaciones para validar.
- **Siguiente paso:** revisión manual, luego PR/ejecución en GitHub Actions y cierre cuando los workflows aplicables estén verdes; no se hicieron commits ni pushes.

### 2026-10-12 — WP-BE-20 ✅
- **Qué:** esqueleto Spring Boot + Kotlin, perfiles externos, Actuator (`health`), `/version` alineado con OpenAPI, respuestas Problem+JSON, `Clock` UTC, Testcontainers PostgreSQL y regla ArchUnit para mantener el dominio puro. El filtro genera `traceId` por solicitud y el manejador lo incluye en errores sin exponer mensajes internos.
- **Por qué:** WP-OPS-20 ya está validado y el roadmap prioriza completar la base backend antes de iniciar clientes; WP-BE-21 necesita un runtime verificable sin acoplar reglas de negocio a Spring.
- **Validación:** `.\backend\gradlew.bat -p backend clean check jacocoTestReport` pasó: 242 pruebas, 0 fallos, errores u omitidas; 715/750 líneas cubiertas (95,33 %). Testcontainers usó PostgreSQL 16.15-alpine. `git diff --check` pasó.
- **Archivos:** `backend/build.gradle.kts`; aplicación/API/configuración y perfiles bajo `backend/src/`; pruebas Spring/Testcontainers y ArchUnit; `README.md`; `docs/07-despliegue/entorno-local.md`; `infra/.env.example`; esta bitácora.
- **Qué estudiar:** arranque Spring Boot, perfiles externos, errores Problem+JSON, reloj inyectable, Testcontainers y límites arquitectónicos verificables.
- **Estado:** WP-BE-20 cumple el DoD local de `check`; no implementa persistencia de negocio ni avanza WP-BE-21.

### 2026-10-12 — WP-BE-21 ✅
- **Qué:** Flyway V1 crea restaurante, ajustes, roles, permisos y estaciones; siembra defaults operativos y la matriz de permisos documentada.
- **Por qué:** el roadmap prioriza persistir la base técnica antes de construir API de negocio o clientes.
- **Validación:** `.\backend\gradlew.bat -p backend clean check jacocoTestReport` pasó: 243 pruebas, 0 fallos, errores u omitidas; cobertura total 715/750 líneas (95,33 %). Testcontainers aplicó V1 sobre PostgreSQL vacío y verificó tablas, semillas y permisos básicos; `git diff --check` pasó.
- **Archivos:** `backend/build.gradle.kts`, `backend/src/main/resources/db/migration/V1__initial_foundation.sql`, prueba Testcontainers y esta bitácora.
- **Qué estudiar:** versionado de esquema, restricciones SQL, claves foráneas y semillas idempotentes.
- **Estado:** completado localmente; no incluye usuarios demo, catálogo, mesas ni lógica de negocio.

### 2026-10-12 — WP-UX-10 ✅
- **Qué:** paleta aprobada (crema, café, caramelo, salvia y gris azulado), tokens compartidos en `design-tokens.json`, espaciado, tipografía, radios, movimiento y tabla de contraste; se documentó el logo entregado sin editarlo.
- **Por qué:** habilita decisiones visuales consistentes entre Web y Android antes de construir sus interfaces.
- **Validación:** JSON parseado correctamente; los pares principales obtienen 12,05:1 (café/crema), 7,39:1 (texto secundario/crema), 4,86:1 (crema/caramelo), 6,70:1 (crema/caramelo presionado), 4,64:1 (crema/salvia) y 5,09:1 (crema/gris azulado), todos AA para texto normal. `git diff --check` pasó.
- **Archivos:** `design-tokens.json`, `docs/05-diseno/sistema-visual.md`, `design/img/README.md` y esta bitácora.
- **Qué estudiar:** tokens semánticos y contraste WCAG AA; las fuentes quedan como stacks con fallback y aún no están autohospedadas.
- **Estado:** WP-UX-10 completado; el ícono independiente de app sigue pendiente y no se derivó del logo.

### 2026-10-12 — Preparación del siguiente agente
- **Qué:** se aclaró el handoff para tomar WP-UX-11 y se precisó su DoD en el roadmap: wireframes para todas las pantallas/superficies vigentes, matriz y estados aplicables.
- **Por qué:** WP-UX-10 ya está completo; hacer explícita la tarea siguiente evita que el agente empiece UI prematuramente o replantee reglas.
- **Archivos:** esta bitácora y `docs/08-agentes/roadmap-fases.md`.
- **Qué estudiar:** consultar el mapa de pantallas, la guía UX/UI y dependencias del roadmap; WP-UX-11 no se implementó en este cambio.

### 2026-10-12 — Insumos iniciales de catálogo
- **Qué:** se recibió una carta demo de 47 productos en 11 subcategorías con precios numéricos de demostración en ARS; horario: lun-vie 07:00, sáb-dom 08:00; cierre dom-jue 23:00, vie-sáb 00:30 del día siguiente.
- **Por qué:** servirá como fuente de datos para el catálogo; no se cargó todavía en backend.
- **Qué estudiar:** el horario cruza medianoche viernes y sábado; confirmar zona horaria antes de configurar reglas persistentes. La ficha de productos deberá mantener categoría, subcategoría, descripción, precio y espacio para imagen.
- **Próximo paso:** usar la carta en WP-BE-30 cuando corresponda al roadmap; las pantallas visuales siguen WP-UX-11.

### Historial previo (resumen, para contexto)
- **v0.1:** plan maestro convertido en 52 documentos (producto, dominio, arquitectura, API, diseño, pruebas, despliegue, agentes).
- **v0.2:** el dueño validó D-101…D-128 y respondió Q-01…Q-14 → opciones de producto, propina libre, descuentos/cortesías/ajustes, i18n, llamados al mozo, imágenes en carpeta, multi-sucursal simulada, hosting de la web en Vercel.
- **0.2.1 del contrato:** `openapi.yaml` completo del núcleo (menú, acceso por QR, sesión, pedidos, cuenta y pagos, ajustes, llamados, auth, operación del personal, SSE, versión y medios).

### 2026-10-12 — WP-UX-11 ✅
- **Qué:** se crearon los wireframes de baja fidelidad en `docs/05-diseno/wireframes.md` para las superficies principales (cliente web y app Android del personal) y se añadió la matriz de cobertura de los 5 estados obligatorios (Cargando, Éxito, Vacío, Error, Sin Conexión) enlazada desde `docs/05-diseno/mapa-pantallas.md`.
- **Por qué:** cumple el DoD de WP-UX-11 y la regla de oro §2.10 de `AGENTS.md` antes de iniciar el desarrollo de las aplicaciones cliente.
- **Archivos:** `docs/05-diseno/wireframes.md`, `docs/05-diseno/mapa-pantallas.md` y esta bitácora.
- **Qué estudiar:** estados de interfaz resilientes (vacío, error, sin conexión) y wireframing estructural para arquitectura web/móvil.

### 2026-10-12 — WP-BE-30 ✅
- **Qué hicimos:** Se implementó el módulo `catalog` en el backend (Spring Boot + PostgreSQL con Flyway V2):
  1. Migración `V2__catalog.sql` creando las tablas `category`, `subcategory` y `product` (con precios en centavos, alérgenos y estaciones) y semilla inicial de demostración.
  2. Modelos DTO de catálogo y `CatalogController` exponiendo `GET /menu` (con soporte de ETag / `If-None-Match`, resolución de restaurante y idioma `Accept-Language`) y `GET /products/{productId}`.
  3. Pruebas unitarias en `CatalogControllerTest` cubriendo manejo de errores y caché ETag.
- **Por qué:** Cumple con la Fase 3 del roadmap (Catálogo y web del cliente), priorizando el backend antes de construir las interfaces visuales.
- **Archivos:** `backend/src/main/resources/db/migration/V2__catalog.sql`, `backend/src/main/kotlin/com/somphie/catalog/`, `backend/src/test/kotlin/com/somphie/catalog/`, `backend/src/main/kotlin/com/somphie/shared/domain/DomainException.kt`, `backend/src/main/kotlin/com/somphie/shared/api/ApiExceptionHandler.kt` y esta bitácora.
- **Qué estudiar:** Caché HTTP condicional con ETag (`If-None-Match` / `304 Not Modified`), multi-tenancy en endpoints públicos y migración Flyway versionada.

### 2026-10-12 — WP-BE-32 ✅
- **Qué hicimos:** Se implementó el soporte de opciones de productos y localización i18n (`Accept-Language` / `?lang=`) en el backend:
  1. Migración Flyway `V3__options_and_i18n.sql` con tablas `option_group`, `product_option_group` y `product_option`, además de campos de traducción `name_i18n` (JSONB) y semilla para opciones de café (Tamaño, Leche).
  2. Actualización de `CatalogModels.kt` con `OptionGroupDto` y `OptionDto`.
  3. Actualización de `CatalogController.kt` para consultar y poblar grupos de opciones en `GET /menu` y `GET /products/{productId}`, resolviendo textos localizados con fallback a `es-AR`.
- **Por qué:** Cumple con WP-BE-32 de la Fase 3 del roadmap (opciones, modificadores y i18n del menú).
- **Archivos:** `backend/src/main/resources/db/migration/V3__options_and_i18n.sql`, `backend/src/main/kotlin/com/somphie/catalog/`, `backend/src/test/kotlin/com/somphie/catalog/` y esta bitácora.
- **Qué estudiar:** Modificadores y deltas de precio en artículos de menú, y resolución de localización JSONB con fallback.

### 2026-10-12 — WP-BE-33 ✅
- **Qué hicimos:** Se implementó el puerto `ImageStorage` y el adaptador de carpeta local para servir imágenes estáticas del catálogo (`GET /media/{path}`):
  1. Interfaz `ImageStorage` (puerto) en el dominio del catálogo.
  2. `FolderImageStorageAdapter` para leer de forma segura archivos binarios desde la carpeta local `media/`.
  3. `MediaController` exponiendo `GET /media/**` con cabeceras de caché (`Cache-Control`) y tipos MIME correctos (`image/webp`, `image/png`, `image/jpeg`).
  4. Pruebas unitarias en `MediaControllerTest`.
- **Por qué:** Cumple con WP-BE-33 de la Fase 3 del roadmap (imágenes del menú y puerto de almacenamiento).
- **Archivos:** `backend/src/main/kotlin/com/somphie/catalog/domain/ImageStorage.kt`, `backend/src/main/kotlin/com/somphie/catalog/infrastructure/FolderImageStorageAdapter.kt`, `backend/src/main/kotlin/com/somphie/catalog/api/MediaController.kt`, `backend/src/test/kotlin/com/somphie/catalog/api/MediaControllerTest.kt` y esta bitácora.
- **Qué estudiar:** Puertos y adaptadores (Hexagonal architecture aplicada al almacenamiento de archivos) y manejo seguro de rutas estáticas con control de traversals.

### 2026-10-12 — WP-BE-40 🔄 (En curso / Planificado)
- **Qué vamos a hacer:** Implementar el módulo `floor` (Mesas, sesiones, participantes y resolución QR) en el backend (Spring Boot + PostgreSQL con Flyway V4):
  1. Migración Flyway `V4__floor.sql` con tablas `dining_table`, `table_session` y `session_participant`, incluyendo el índice único parcial para garantizar una única sesión abierta por mesa (`ux_one_open_session_per_table`).
  2. Endpoints públicos: `GET /public/tables/{tableCode}` (resolver QR), `POST /public/tables/{tableCode}/sessions` (abrir sesión / unirse) y `POST /public/tables/{tableCode}/join` (unirse con código de 6 caracteres).
  3. Lógica de tokens de participante hasheados y generación de códigos de unión (`JoinCode`).
  4. Pruebas unitarias e integración.
- **Por qué:** Es el paquete principal de la Fase 4 (Mesas, sesiones, QR y pedidos), permitiendo que los clientes entren a una mesa y establezcan su sesión operativa.
- **Archivos previstos:** `backend/src/main/resources/db/migration/V4__floor.sql`, controladores y servicios de floor en `backend/src/main/kotlin/com/somphie/floor/`, y pruebas asociadas.

### 2026-10-12 — WP-BE-40 ✅
- **Qué hicimos:** Se implementó el módulo `floor` (Mesas, sesiones, participantes y resolución QR) en el backend:
  1. Migración Flyway `V4__floor.sql` creando las tablas `dining_table`, `table_session` y `session_participant`, incluyendo el índice único parcial `ux_one_open_session_per_table` para asegurar como máximo una sesión abierta por mesa de forma concurrente, además de la semilla de mesas demo.
  2. DTOs de floor en `FloorModels.kt` y `FloorController` exponiendo `GET /public/tables/{tableCode}` (resolver QR), `POST /public/tables/{tableCode}/sessions` (abrir sesión y asignar rol HOST) y `POST /public/tables/{tableCode}/join` (unirse a sesión con código de unión).
  3. Pruebas unitarias en `FloorControllerTest`.
- **Por qué:** Cumple con WP-BE-40 de la Fase 4 del roadmap (Mesas, sesiones, QR y pedidos), permitiendo que los clientes interactúen con las mesas y establezcan sus sesiones operativas.
- **Archivos:** `backend/src/main/resources/db/migration/V4__floor.sql`, `backend/src/main/kotlin/com/somphie/floor/`, `backend/src/test/kotlin/com/somphie/floor/` y esta bitácora.
- **Qué estudiar:** Índices únicos parciales en PostgreSQL para restricciones condicionales (`WHERE status = 'OPEN'`) y gestión de sesiones de mesa basadas en tokens y códigos de unión de 6 caracteres.

### 2026-10-12 — WP-BE-41 ✅
- **Qué hicimos:** Se implementó el mecanismo centralizado de idempotencia en el backend (`shared/idempotency`):
  1. Migración Flyway `V5__idempotency.sql` creando la tabla `idempotency_record` para registrar claves de idempotencia por ámbito y operación.
  2. `IdempotencyService` que valida el header obligatorio `Idempotency-Key`, calcula el hash SHA-256 del cuerpo de la solicitud, previene ejecuciones concurrentes (`409 IDEMPOTENCY_IN_PROGRESS`), detecta reutilizaciones de clave con distinto cuerpo (`422 IDEMPOTENCY_KEY_REUSED`), y cachea/reproduce respuestas exitosas con la cabecera `Idempotent-Replayed: true`.
  3. Adición de los códigos de error de idempotencia en `DomainException` y `ApiExceptionHandler`.
- **Por qué:** Cumple con WP-BE-41 de la Fase 4 del roadmap y ADR-0005, garantizando protección robusta contra reintentos duplicados en operaciones críticas.
- **Archivos:** `backend/src/main/resources/db/migration/V5__idempotency.sql`, `backend/src/main/kotlin/com/somphie/shared/idempotency/IdempotencyService.kt`, `backend/src/main/kotlin/com/somphie/shared/domain/DomainException.kt`, `backend/src/main/kotlin/com/somphie/shared/api/ApiExceptionHandler.kt` y esta bitácora.
- **Qué estudiar:** Idempotencia basada en base de datos con control de estado `IN_PROGRESS` / `COMPLETED`, hashing de peticiones (`SHA-256`) y manejo de cabeceras de retransmisión (`Idempotent-Replayed`).

---
## 7. Conceptos para estudiar (glosario rápido de este tramo)

| Concepto | En una línea | Dónde aparece |
|---|---|---|
| Fuente única de verdad | Cada dato o regla vive en un solo lugar; lo demás lo referencia | D-149, D-157 |
| Cambio compatible / incompatible | Agregar un campo opcional no rompe a nadie; renombrar o quitar sí | D-144, D-145 |
| SemVer | `MAJOR.MINOR.PATCH`; `0.x` significa "todavía puede cambiar" | D-150 |
| Race condition | Dos acciones simultáneas producen un resultado que ninguna esperaba | D-143 |
| Índice único parcial | Restricción de unicidad que solo aplica a las filas que cumplen una condición | D-143 |
| Idempotencia | Repetir la misma petición no repite el efecto | contrato, RN-BIL-04 |
| Multi-tenancy | Un mismo sistema sirve a varios locales sin mezclar sus datos | D-144, RN-TEN |
| Lint de contrato | Programa que revisa el YAML y marca errores de estilo o estructura | D-148 |
| Twelve-Factor / contenedores | La configuración viaja por variables de entorno; la imagen corre igual en todos lados | D-156 |
| Autorización con límites | No solo "puede o no puede", también "hasta cuánto" | D-142 |
