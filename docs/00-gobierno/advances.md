# Advances — bitácora y contexto para el próximo agente

> **Léelo justo después de `AGENTS.md`.** Dice dónde está el proyecto, qué se decidió, qué falta y qué paso toca.
> **Regla de uso:** antes de cada cambio, marcar el paso como 🔄 y listar los archivos que se van a tocar; al terminar, marcarlo ✅ y agregar una entrada en la bitácora (sección 6).
> Cada cambio se anota con tres partes: **Qué** se hizo, **Por qué**, y **Qué estudiar** (el dueño del proyecto está aprendiendo).

Leyenda: ✅ hecho · 🔄 en curso · ⬜ pendiente · 🟡 propuesto · 🔴 bloqueado

---
## 1. Estado actual (snapshot al 2026-10-11)

### 1.1 Qué existe
| Pieza | Estado | Dónde |
|---|---|---|
| Plan maestro original | ✅ | `Plan_Maestro_Sistema_Cafeteria.md/.pdf` |
| Documentación v0.1 (producto, dominio, arquitectura, API, diseño, pruebas, despliegue, agentes) | ✅ | `docs/` |
| Documentación v0.2 (opciones de producto, propina, ajustes, i18n, llamados al mozo, imágenes en carpeta, multi-sucursal simulada) | ✅ | secciones "v0.2" dentro de cada doc |
| Decisiones D-101…D-141 y preguntas Q-01…Q-14 resueltas | ✅ | `docs/00-gobierno/decisiones.md` |
| ADRs 0001–0009 (propuestas) | ✅ | `docs/00-gobierno/adr/` |
| Contrato `openapi.yaml` **0.2.2** (WP-ARQ-10) | ✅ (sin validar con lint) | `docs/04-api/openapi.yaml` |
| Config de lint `redocly.yaml` | ✅ (con una posible clave mal escrita, ver H-09) | raíz |
| `CHANGELOG.md` del contrato hasta 0.2.2 | ✅ | `docs/04-api/CHANGELOG.md` |
| Estructura de carpetas y `AGENTS.md` por carpeta | ✅ | `backend/`, `web/`, `android/` |
| Archivos de marca: `ic_icon.svg` (1,54 MB) y `boceto_v.1.0.png` (1,75 MB) | ✅ existen; **sin revisar** (superan el límite de lectura de 1 MB del agente) | `design/img/` |

### 1.2 Qué NO existe todavía
- Código de `backend/`, `web/` y `android/` (solo tienen `AGENTS.md`).
- `infra/docker-compose.yml`, CI (`.github/`), plantillas de PR.
- Tokens de diseño (paleta, tipografía, espaciado) derivados de la marca. Los archivos de marca **sí existen** (ver 1.1), pero `design/img/README.md` todavía pide `ic_icon.png` y hoy el ícono es `ic_icon.svg`.
- Catálogo de acciones de auditoría.
- Pruebas de cualquier tipo.

### 1.3 Advertencias para quien continúe
1. **La fuente de verdad es este repositorio local.** Cualquier copia en otro lado (zips, sandboxes, descargas) puede estar desactualizada. En particular, **no sobrescribir** `CHANGELOG.md`, `openapi.yaml` ni `reglas-negocio.md` con versiones viejas.
2. Existen **copias duplicadas en la raíz** (`decisiones.md`, `cambios-v0.2.md`, `roadmap-fases.md`, `README.md`) además de las de `docs/`. La versión oficial es la de `docs/` (ver Paso 7).
3. Las secciones "v0.2" están **apendadas al final** de varios documentos y tienen precedencia sobre el texto anterior. Se integrarán en el Paso 8.
4. Nadie debe cambiar nombres de estados, errores o campos del contrato por su cuenta (`AGENTS.md` §2).

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
| 9 | ⬜ | Puerta de Fase 1 y arranque de Fase 1: dominio puro (WP-BE-10) y tokens de diseño (WP-UX-10; ver Q-21; incluye actualizar `design/img/README.md` y D-134, que nombran `ic_icon.png`) | `backend/`, `design/` | Checklist de `arquitecto.md` | — |

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

### Historial previo (resumen, para contexto)
- **v0.1:** plan maestro convertido en 52 documentos (producto, dominio, arquitectura, API, diseño, pruebas, despliegue, agentes).
- **v0.2:** el dueño validó D-101…D-128 y respondió Q-01…Q-14 → opciones de producto, propina libre, descuentos/cortesías/ajustes, i18n, llamados al mozo, imágenes en carpeta, multi-sucursal simulada, hosting de la web en Vercel.
- **0.2.1 del contrato:** `openapi.yaml` completo del núcleo (menú, acceso por QR, sesión, pedidos, cuenta y pagos, ajustes, llamados, auth, operación del personal, SSE, versión y medios).

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
