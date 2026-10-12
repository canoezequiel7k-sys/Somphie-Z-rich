# Reglas de negocio

Formato: `RN-MÓDULO-nn`. Cada regla indica **dónde se impone** (D = dominio, A = aplicación/caso de uso, DB = restricción de base de datos, UI = solo ayuda visual). Si una regla crítica solo está en UI, es un defecto.

**Valores por defecto configurables** (`restaurant_settings`, D-106): `maxLinesPerOrder=30`, `maxQtyPerLine=20`, `maxNoteLength=140`, `maxPendingOrdersPerSession=3`, `maxParticipants=12`, `orderPendingTtlMin=15`, `sessionIdleTtlMin=30`, `deliveredUndoWindowMin=5`, `closedSessionReadHours=24`, `minPaymentMinor=100`, `delayedItemSlaMin=10`, `businessDayCutoff=04:00`. Desde v0.2 se suman: `maxTipPercent=100` (RN-TIP-03), `maxStaffDiscountPercent=15` (RN-ADJ-06, D-142) y `maxStaffAdjustmentMinor` (opcional, **sin tope por defecto**, D-142).

---
## RN-ACC — Acceso por QR y participantes (módulo `floor`)

| ID | Regla | Dónde |
|---|---|---|
| RN-ACC-01 | El QR codifica `https://<web>/t/<tableCode>`. `tableCode` es aleatorio, opaco, ≥ 128 bits, único; **no** deriva del número de mesa | D/DB |
| RN-ACC-02 | `tableCode` solo permite: ver menú, ver si la mesa está libre/ocupada, abrir sesión o unirse. **Nunca** expone pedidos, nombres ni saldo | A |
| RN-ACC-03 | El dueño puede regenerar `tableCode` (invalida QR impresos viejos). Las sesiones abiertas siguen funcionando con su token | A |
| RN-ACC-04 | Una mesa inactiva (`active=false`) no admite sesiones nuevas (`TABLE_UNAVAILABLE`) | A |
| RN-ACC-05 | Escanear una mesa **sin sesión abierta** permite abrirla, siempre que el local esté abierto. Si `requireStaffToOpenSession=true`, la sesión nace en espera de confirmación del personal (🟡, apagado por defecto) | A |
| RN-ACC-06 | Escanear una mesa **con sesión abierta** exige `joinCode` o token válido de esa sesión (`JOIN_CODE_REQUIRED`) | A |
| RN-ACC-07 | `joinCode`: 6 caracteres sin ambiguos; válido solo con sesión `OPEN`; rotable por host/personal; 5 intentos fallidos / 10 min por IP+mesa → bloqueo 10 min (`JOIN_CODE_RATE_LIMITED`) | A |
| RN-ACC-08 | Token de invitado: 256 bits, se guarda **hasheado**, atado a (sesión, participante); vence a las 24 h del cierre (lectura); revocable | D/DB |
| RN-ACC-09 | Máximo de participantes por sesión (`SESSION_FULL`) | A |
| RN-ACC-10 | El `HOST` (si existe) y el personal pueden revocar participantes y rotar el código. **El host no puede revocarse a sí mismo** (`HOST_CANNOT_BE_REVOKED`). Mientras una sesión no tiene HOST (RN-ACC-14), esas acciones las conserva el personal | A |
| RN-ACC-11 | Un participante revocado pierde acceso inmediato; sus pedidos ya enviados **se conservan** | A |
| RN-ACC-12 | `displayName` del invitado: 1–30 caracteres, sin HTML; se muestra en "pedido de X" | A |
| RN-ACC-13 | Si dos personas escanean a la vez una mesa libre, **una** abre la sesión y la otra recibe `SESSION_ALREADY_OPEN` y el flujo de unirse | DB |
| RN-ACC-14 | Una sesión abierta **por el personal** (CU-30, D-114) nace **sin HOST**. El primer participante que se une pasa a ser `HOST`; los siguientes son `MEMBER` (D-143). En una sesión abierta por QR, quien la abre es el `HOST` | A |
| RN-ACC-15 | A lo sumo **un** `HOST` por sesión, garantizado por un índice único parcial (`WHERE role = 'HOST'`). Si dos personas se unen a la vez a una sesión sin HOST, gana una; la otra queda como `MEMBER` **en la misma transacción**, sin error (se intenta insertar y se maneja el conflicto; no se consulta primero para insertar después) | DB |

## RN-SES — Sesiones y mesas

| ID | Regla | Dónde |
|---|---|---|
| RN-SES-01 | A lo sumo **una** sesión `OPEN` por mesa | DB (índice único parcial) |
| RN-SES-02 | Una sesión pertenece a una única mesa y **nunca** se reutiliza | D |
| RN-SES-03 | La sesión agrupa pedidos, pagos y participantes; una visita nueva no hereda nada | D |
| RN-SES-04 | Motivos de cierre: `SETTLED`, `EMPTY`, `EXPIRED_EMPTY`, `FORCED` | D |
| RN-SES-05 | Cierre normal exige: sin pedidos `PENDING_REVIEW`, sin ítems `QUEUED/IN_PREPARATION/READY`, `balance == 0` | A (bajo `FOR UPDATE`) |
| RN-SES-06 | Cierre forzado: solo `session.force_close`, motivo obligatorio, cancela pendientes y ítems activos, queda auditado y marcado en el comprobante. **Pagos y propinas:** los pagos `SUCCEEDED` y sus propinas **se conservan** (no se anulan solos). Si queda saldo > 0, se registra como **no cobrado** en la auditoría y en el comprobante. Si tras cancelar queda saldo < 0, en la **misma transacción** se registra un **reembolso simulado automático** por el exceso (sin devolver propina), auditado con el actor y marcado "automático por cierre forzado" (D-159) | A |
| RN-SES-07 | Sesión `CLOSED`: no admite pedidos, pagos ni participantes. Solo lectura con token 24 h | D/A |
| RN-SES-08 | Expira sola si no hay pedidos facturables ni pendientes y pasaron `sessionIdleTtlMin` sin **actividad** (crear pedido, unirse, acción del personal). Consultar/poll **no** cuenta como actividad | A (job) |
| RN-SES-09 | Mesa y sesión se identifican con IDs distintos en toda API | D |
| RN-SES-10 | No se puede desactivar ni borrar una mesa con sesión `OPEN` (`TABLE_HAS_OPEN_SESSION`) | A |

## RN-ORD — Pedidos (módulo `orders`)

| ID | Regla | Dónde |
|---|---|---|
| RN-ORD-01 | Un pedido pertenece a una sesión `OPEN`; lo crea un participante de ella o el personal | A |
| RN-ORD-02 | Validaciones: ≥ 1 línea; ≤ `maxLinesPerOrder`; cantidad 1..`maxQtyPerLine`; producto existe, `active` y `available`; nota ≤ `maxNoteLength`; **opciones** válidas según RN-OPT-03 (grupo asignado al producto, activas, disponibles, reglas del grupo) y como máximo 10 por línea (RN-OPT-06) | A |
| RN-ORD-03 | **El servidor calcula todos los precios**, incluido el de las opciones (`unitPriceMinor = precio base + Σ deltas`, RN-OPT-04). El cliente envía `acknowledgedTotalMinor`; si no coincide con el cálculo → `409 PRICE_CHANGED` con precios vigentes (un cambio en una opción o en su precio también lo dispara, RN-OPT-07); no se crea nada | A |
| RN-ORD-04 | **Snapshot** por ítem: `productName` (y `productNameI18n`, RN-I18N-05), `stationId`, `basePriceMinor`, `optionsDeltaMinor`, `unitPriceMinor` y `optionsSnapshot` (RN-OPT-04) se copian al crear. Cambios de catálogo posteriores (producto, precio u opciones) no afectan | D |
| RN-ORD-05 | Se crea en `PENDING_REVIEW`; sus ítems en `PENDING` | D |
| RN-ORD-06 | Máx. `maxPendingOrdersPerSession` pedidos `PENDING_REVIEW` por sesión (`ORDER_PENDING_LIMIT_REACHED`) | A |
| RN-ORD-07 | Fuera de horario de atención, un cliente no puede crear pedidos (`RESTAURANT_CLOSED`). El personal sí puede (con aviso) | A |
| RN-ORD-08 | Solo `PENDING_REVIEW` puede aceptarse/rechazarse; **gana la primera decisión**, la segunda recibe `ORDER_ALREADY_DECIDED` con quién y cuándo | A/DB |
| RN-ORD-09 | Rechazo: `reasonCode` ∈ {`OUT_OF_STOCK`, `KITCHEN_CLOSED`, `ITEM_UNAVAILABLE`, `OTHER`} + texto opcional ≤ 140. **Se conserva** en historial | D |
| RN-ORD-10 | Aceptar puede **excluir ítems** (con motivo): quedan `CANCELLED` y no cobran; si se excluyen todos → `ORDER_ALL_ITEMS_EXCLUDED` (usar rechazo) | A |
| RN-ORD-11 | El cliente (autor o host) puede cancelar **solo** mientras esté `PENDING_REVIEW` | A |
| RN-ORD-12 | `PENDING_REVIEW` sin decisión tras `orderPendingTtlMin` pasa a `EXPIRED` (job). El cliente puede reenviar | A (job) |
| RN-ORD-13 | Tras aceptar, cantidades y precios **no se modifican**. Solo se cancelan ítems (personal) | D |
| RN-ORD-14 | `Order.status` está materializado y se **recalcula en la misma transacción** que cualquier cambio de ítem (ver estados §4) | D |
| RN-ORD-15 | `displayCode` `#NNN` secuencial por (restaurante, día de negocio), generado atómicamente | DB |
| RN-ORD-16 | La cola del personal muestra `productCurrentlyAvailable` por ítem para que se advierta si algo dejó de estar disponible tras el envío | A |
| RN-ORD-17 | Crear pedido es **idempotente** por `Idempotency-Key` + participante: el reintento devuelve la misma respuesta y marca `Idempotent-Replayed: true` | A/DB |
| RN-ORD-18 | Un pedido tomado por el personal (`create_on_behalf`) nace `ACCEPTED` (auto-aceptado) y queda auditado con el actor | A |
| RN-ORD-19 | Un pedido rechazado/cancelado/expirado **jamás** aparece en la cuenta | D |
| RN-ORD-20 | Los participantes ven **todos** los pedidos de su sesión (cuenta compartida, D-007) | A |

## RN-PRE — Preparación y entrega (módulos `preparation` + `orders`)

| ID | Regla | Dónde |
|---|---|---|
| RN-PRE-01 | `item.stationId` = estación del producto **al momento del pedido** (snapshot) | D |
| RN-PRE-02 | `item.prepare` solo sobre ítems de **estaciones asignadas** al usuario (u `OWNER`) | A |
| RN-PRE-03 | Solo se opera sobre ítems de pedidos aceptados en sesión `OPEN` | A |
| RN-PRE-04 | Transiciones válidas únicamente las de `estados-y-transiciones.md` §3; el resto → `ITEM_INVALID_TRANSITION` y sin cambios | D |
| RN-PRE-05 | Las transiciones usan actualización condicional por estado esperado (dos personas tocando el mismo ítem: gana una) | DB |
| RN-PRE-06 | `deliver` lo hace quien tenga `item.deliver`; no requiere ser de la estación | A |
| RN-PRE-07 | Cancelar un ítem exige motivo; no se cancela `DELIVERED`. Si ya hubo pagos y el saldo queda < 0 → se resuelve con reembolso | A |
| RN-PRE-08 | Cola por estación en orden FIFO por `acceptedAt`; ítems en cola más de `delayedItemSlaMin` se marcan `delayed=true` | A |
| RN-PRE-09 | Deshacer `DELIVERED→READY` solo dentro de `deliveredUndoWindowMin` | A |
| RN-PRE-10 | Todo cambio guarda actor y timestamp (para métricas de tiempos) | D |

## RN-BIL — Cuenta y pagos (módulo `billing`)

| ID | Regla | Dónde |
|---|---|---|
| RN-BIL-01 | `charges = Σ lineTotalMinor` de ítems no cancelados de pedidos `ACCEPTED…DELIVERED` (`lineTotalMinor = unitPriceMinor × quantity`, y `unitPriceMinor` ya incluye opciones, RN-OPT-04). Usa **precios históricos**, nunca el catálogo actual | D |
| RN-BIL-02 | `balance = charges − adjustments − paid` (v0.2) calculado **dentro de la transacción** con la sesión bloqueada. La propina **no** entra en esta fórmula (RN-TIP-02) | A/DB |
| RN-BIL-03 | Pago con monto principal (**`amountMinor > 0`**): entero `min(minPaymentMinor, balance) ≤ amount ≤ balance` (si el saldo es menor al mínimo, se paga exactamente el saldo, D-158); si `balance ≤ 0` → `PAYMENT_NOTHING_TO_PAY`; si excede → `PAYMENT_EXCEEDS_BALANCE` (devuelve saldo actual). **Con `amountMinor = 0` (pago solo propina) esta regla no aplica**: rige RN-TIP-01/RN-TIP-04 y no se rechaza por saldo 0 (D-146) | A |
| RN-BIL-04 | Pago idempotente por `Idempotency-Key`; un doble toque **no** cuenta dos veces. Dos pagos **intencionales** distintos del mismo monto son válidos si el saldo alcanza | A/DB |
| RN-BIL-05 | `SimulatedPaymentProvider` aprueba siempre, salvo en modo demo donde un **monto principal** (`amountMinor`) con centavos terminados en `13` simula rechazo (`PAYMENT_DECLINED`, queda registro `FAILED` y no altera saldo); la propina no se evalúa y un pago solo propina nunca se rechaza por esta regla (D-125). Efectivo (`CASH_COUNTER`) lo registra el personal | A |
| RN-BIL-06 | Anular un pago: solo `SUCCEEDED`, sesión `OPEN`, motivo obligatorio; el saldo se recalcula y **su propina también se anula** (RN-TIP-05) | A |
| RN-BIL-07 | Reembolso: `payment.refund`, sesión `OPEN`, monto ≤ pagado neto, motivo obligatorio. Se usa cuando `balance < 0`. **No devuelve propina** (RN-TIP-05) | A |
| RN-BIL-08 | Sesión `CLOSED` no admite pagos | A |
| RN-BIL-09 | Comprobante PDF **no fiscal**: solo para sesiones `CLOSED`; incluye negocio, fecha/hora, id de visita, mesa, ítems con precios históricos (y sus opciones), ajustes con su etiqueta pública (RN-ADJ-10), totales, pagos, **propina en línea aparte** (RN-TIP-07), y marca "cierre forzado" (con saldo no cobrado, si lo hubo) si aplica | A |
| RN-BIL-10 | Dividir la cuenta es una **ayuda de UI** (sugerir partes iguales; el resto al primero). El servidor no guarda asignaciones por persona | UI |
| RN-BIL-11 | Un pago registra `payerParticipantId` (si lo hizo un participante) o `payerLabel` libre (efectivo) | D |
| RN-BIL-12 | Nunca se solicita ni almacena número de tarjeta, CVV ni vencimiento | A |

## RN-CAT — Catálogo

| ID | Regla | Dónde |
|---|---|---|
| RN-CAT-01 | Producto: nombre único por categoría, `priceMinor > 0`, 1 categoría, 1 estación | A/DB |
| RN-CAT-02 | Productos y categorías se **archivan** (`active=false`), no se borran si tienen historia | A |
| RN-CAT-03 | Cambiar un precio no altera pedidos existentes (RN-ORD-04) | D |
| RN-CAT-04 | `available=false` mantiene el producto visible como "Agotado" y no pedible | A |
| RN-CAT-05 | Cualquier cambio de catálogo incrementa `catalogVersion` (ETag del menú) | A |
| RN-CAT-06 | Una categoría con productos activos no se archiva (`CATEGORY_NOT_EMPTY`) | A |
| RN-CAT-07 | Imagen: ≤ 1 MB procesada, formatos webp/jpg/png; **texto alternativo obligatorio** | A |
| RN-CAT-08 | Alérgenos: de una lista controlada de 14 (D-137); verificarla contra el Código Alimentario Argentino antes de publicar | A |

## RN-IDN — Identidad

| ID | Regla | Dónde |
|---|---|---|
| RN-IDN-01 | Solo el dueño crea cuentas de personal | A |
| RN-IDN-02 | Contraseña ≥ 10 caracteres; Argon2id (o BCrypt cost ≥ 12 como alternativa); no se registra ni se devuelve | A |
| RN-IDN-03 | Respuestas de login/registro/olvido **no revelan** si un email existe | A |
| RN-IDN-04 | Login: 5 fallos / 15 min por cuenta+IP → bloqueo temporal (`ACCOUNT_TEMPORARILY_LOCKED`) | A |
| RN-IDN-05 | Access token 15 min; refresh 30 d **rotativo**; reutilizar un refresh ya usado revoca toda la familia (`REFRESH_TOKEN_REUSED`) | A |
| RN-IDN-06 | Usuario deshabilitado: tokens inválidos de inmediato (`tokenVersion`) | A |
| RN-IDN-07 | Siempre ≥ 1 `OWNER` activo (`LAST_OWNER_PROTECTED`) | A |
| RN-IDN-08 | Cliente registrado debe verificar email para acceder a extras | A |
| RN-IDN-09 | Contraseña temporal de personal obliga a cambiarla en el primer login | A |

## RN-AUD — Auditoría

| ID | Regla |
|---|---|
| RN-AUD-01 | Se audita: aceptar/rechazar/cancelar pedidos, cambios de estado de ítems, pagos/anulaciones/reembolsos, cambios de catálogo, apertura/cierre/cierre forzado de sesión, revocación de participantes, cambios de usuarios/roles/estaciones, regeneración de QR, cambios de ajustes, logins fallidos reiterados |
| RN-AUD-02 | Registro **append-only** (el usuario de la app no tiene `UPDATE/DELETE` sobre la tabla) |
| RN-AUD-03 | Campos: `occurredAt`, `actorType` (`USER`/`PARTICIPANT`/`SYSTEM`), `actorId`, `action`, `targetType`, `targetId`, `reason?`, `beforeSummary?`, `afterSummary?`, `traceId` |
| RN-AUD-04 | Nunca se guardan contraseñas, tokens, ni datos personales innecesarios |
| RN-AUD-05 | Se escribe en la **misma transacción** que la acción auditada |

## RN-RT — Tiempo real

| ID | Regla |
|---|---|
| RN-RT-01 | Los eventos son **pistas**: nunca reemplazan la consulta ni la validación |
| RN-RT-02 | Cada flujo se autoriza por alcance (participante→su sesión; staff→permiso+estación) |
| RN-RT-03 | Los clientes deduplican por `eventId` y toleran orden alterado usando `version` |
| RN-RT-04 | Tras reconectar, el cliente reconsulta el estado y reanuda con `Last-Event-ID` |
| RN-RT-05 | Si falla SSE, la app cae a polling (5–10 s) sin romper la UI |
| RN-RT-06 | Un fallo al emitir un evento **no** revierte la operación de negocio |

## RN-NOT — Email y notificaciones

| ID | Regla |
|---|---|
| RN-NOT-01 | El envío de email va por un puerto `EmailSender`; un fallo **no** deshace la operación guardada |
| RN-NOT-02 | En desarrollo usa Mailpit; en producción, un proveedor con límites revisados |
| RN-NOT-03 | Reintentos con tope y registro del resultado |

---
# Reglas añadidas en v0.2

## RN-OPT — Opciones y modificadores (`catalog` + `orders`)

| ID | Regla | Dónde |
|---|---|---|
| RN-OPT-01 | Un `OptionGroup` es **reutilizable** y se asigna a uno o varios productos. Atributos: `selectionType` (`SINGLE`/`MULTIPLE`), `required`, `minSelections`, `maxSelections` | D |
| RN-OPT-02 | Cada `Option`: nombre, `priceDeltaMinor ≥ 0`, `available`, `active`, `isDefault` | D |
| RN-OPT-03 | Por línea el cliente envía `optionIds[]`. El servidor valida: pertenecen a un grupo asignado a **ese** producto, están activas y disponibles, y se cumplen las reglas del grupo (`SINGLE` ≤ 1; `required` ⇒ ≥ `minSelections`; `MULTIPLE` entre min y max) | A |
| RN-OPT-04 | `unitPriceMinor = product.priceMinor + Σ priceDeltaMinor`. Se guarda snapshot: `basePriceMinor`, `optionsDeltaMinor`, `unitPriceMinor`, `optionsSnapshot` (grupo, opción, delta) | D |
| RN-OPT-05 | Mismo producto con distintas opciones o nota = **líneas distintas** | UI/A |
| RN-OPT-06 | Máximo 10 opciones por línea | A |
| RN-OPT-07 | Cambiar una opción o su precio incrementa `catalogVersion` y queda cubierto por `PRICE_CHANGED` | A |
| RN-OPT-08 | Las opciones se muestran en la cola de preparación (`optionsSummary`), la cuenta y el comprobante | A |
| RN-OPT-09 | El personal con `availability.toggle` puede activar/desactivar **opciones** (ej. "sin leche de avena") | A |
| RN-OPT-10 | Si un grupo `required` queda sin opciones disponibles, el producto deja de ser pedible (`PRODUCT_UNAVAILABLE`) | A |
| RN-OPT-11 | Archivar opciones/grupos no altera pedidos históricos (snapshot) | D |

## RN-TIP — Propina libre

| ID | Regla | Dónde |
|---|---|---|
| RN-TIP-01 | La propina es **libre y voluntaria**: `tipMinor ≥ 0` adjunta a un pago, o un pago **solo propina** (`amountMinor = 0`, `tipMinor > 0`) | A |
| RN-TIP-02 | La propina **no reduce el saldo** ni es un cargo; se registra aparte | D |
| RN-TIP-03 | Tope de cordura: `tipMinor ≤ maxTipPercent × charges` (por defecto 100 %; `charges` = **cargos brutos**, sin descontar ajustes) → `TIP_EXCEEDS_LIMIT`. Sin cargos no se acepta propina (`TIP_NOT_ALLOWED`) | A |
| RN-TIP-04 | Si `amountMinor > 0` aplican las reglas de RN-BIL-03 (el mínimo de pago, incluida su excepción D-158, es del monto principal, no de la propina); si es 0, `tipMinor > 0` es obligatorio y **no** se rechaza por saldo 0 (D-146) | A |
| RN-TIP-05 | Anular un pago anula también su propina. Los reembolsos **no** devuelven propina | A |
| RN-TIP-06 | Sesión cerrada no admite propina | A |
| RN-TIP-07 | El comprobante muestra la propina **separada** de la cuenta | A |
| RN-TIP-08 | Reportes: propinas se informan aparte de las ventas. Sin atribución a empleado en el MVP (D-153); la UI ofrece montos rápidos como ayuda, el servidor solo recibe `tipMinor` | A |
| RN-TIP-09 | Mismas garantías de idempotencia y bloqueo que cualquier pago; si el monto principal falla, **la propina tampoco** se registra (operación atómica) | A/DB |

## RN-ADJ — Descuentos, cortesías y ajustes

| ID | Regla | Dónde |
|---|---|---|
| RN-ADJ-01 | Tipos: `DISCOUNT_PERCENT`, `DISCOUNT_AMOUNT`, `COURTESY_ITEM`, `MANUAL_ADJUSTMENT`. Todos **restan**; no hay recargos en el MVP | D |
| RN-ADJ-02 | Solo personal con permiso, con sesión `OPEN`, con `reasonCode` obligatorio (+ texto opcional ≤ 140) | A |
| RN-ADJ-03 | El importe se **calcula y congela al aplicar** (un porcentaje se calcula sobre `charges − adjustments` vigentes en ese instante y se **redondea al centavo más cercano**, la mitad hacia arriba). Pedidos posteriores **no** heredan el descuento | D |
| RN-ADJ-04 | `COURTESY_ITEM` apunta a un ítem **aceptado** (no `PENDING`: su pedido todavía no es un cargo, RN-BIL-01), no cancelado y sin cortesía previa; importe = `lineTotalMinor`. Si no cumple → `ADJUSTMENT_ITEM_INVALID` | D |
| RN-ADJ-05 | Un ajuste no puede dejar el saldo negativo: `adjustment ≤ balance` (`ADJUSTMENT_EXCEEDS_BALANCE`). Compensar a quien ya pagó se hace con **reembolso** | A |
| RN-ADJ-06 | `adjustment.apply` está limitado por `maxStaffDiscountPercent` (**15 %**, D-142). Es un límite **acumulado**: la suma de los ajustes vigentes (no anulados) que ese Mozo aplicó sin `adjustment.apply_any` no puede superar ese porcentaje de los **cargos brutos** (`charges`) de la sesión. `maxStaffAdjustmentMinor` es un tope absoluto **opcional** (sin tope por defecto). Superar el límite exige `adjustment.apply_any` (`ADJUSTMENT_LIMIT_EXCEEDED`); el Dueño no tiene límite | A |
| RN-ADJ-07 | Se puede **anular** (`VOIDED`) con motivo mientras la sesión esté `OPEN`; el Mozo solo los suyos y dentro de 10 min, el Dueño cualquiera | A |
| RN-ADJ-08 | Cancelar un ítem con cortesía **anula esa cortesía en la misma transacción** | A |
| RN-ADJ-09 | Aplicar/anular toman lock `FOR UPDATE` de la sesión, son idempotentes y quedan auditados | A/DB |
| RN-ADJ-10 | El cliente ve "Descuento" / "Cortesía de la casa"; el **motivo interno** solo lo ve el personal | A |
| RN-ADJ-11 | Si luego de un ajuste una cancelación deja el saldo < 0, se resuelve con reembolso o anulando el ajuste antes de cerrar | A |
| RN-ADJ-12 | Reportes: descuentos por tipo, motivo y actor (control de abusos) | A |

## RN-WCL — Llamar al mozo

| ID | Regla |
|---|---|
| RN-WCL-01 | Un participante puede crear un llamado con `reason` ∈ {`BILL`, `WATER`, `HELP`, `OTHER`} (+ texto ≤ 80) |
| RN-WCL-02 | Un solo llamado activo (`OPEN`/`ACKNOWLEDGED`) por sesión y motivo; tras resolverlo, espera de 60 s (`WAITER_CALL_COOLDOWN`) |
| RN-WCL-03 | Estados `OPEN → ACKNOWLEDGED → RESOLVED`; expira a los 30 min (`EXPIRED`) |
| RN-WCL-04 | Idempotente y con rate limit. Solo en sesión `OPEN` |
| RN-WCL-05 | Lo ven los usuarios con `waiter_call.handle`; evento `waiter.called`; el tablero muestra una insignia |
| RN-WCL-06 | Es solo un aviso: no cambia pedidos ni cuenta. `BILL` es útil para disparar el cobro y el cierre |

## RN-I18N — Idiomas (web cliente)

| ID | Regla |
|---|---|
| RN-I18N-01 | Web cliente: `es-AR` (por defecto) y `en`; `de` posterior. Panel admin y app Android: solo `es-AR` |
| RN-I18N-02 | Textos de UI por clave; los **errores se localizan en el cliente por `code`** (nunca por `title`/`detail`) |
| RN-I18N-03 | Catálogo con traducciones (`category/product/option_group/option _translation`); si falta una, fallback a `es-AR` |
| RN-I18N-04 | `GET /menu` usa `Accept-Language` (o `?lang=`), devuelve textos ya localizados y el `locale` efectivo |
| RN-I18N-05 | `OrderItem` guarda `productName` (es-AR, para personal y cuenta) **y** `productNameI18n` (jsonb) para que el cliente vea su historial en su idioma sin depender del catálogo vivo |
| RN-I18N-06 | Formatos de moneda/fecha por `Intl`; moneda siempre ARS |
| RN-I18N-07 | El idioma se elige por el navegador, con selector manual guardado en `localStorage`. El carrito se guarda por `productId`, no por nombre |

## RN-IMG — Imágenes en carpeta

| ID | Regla |
|---|---|
| RN-IMG-01 | Puerto `ImageStorage`; adaptador de **carpeta** (`media/`, servida por el backend en dev) |
| RN-IMG-02 | El producto guarda `imagePath` relativo + `imageVersion`; el cliente arma la URL con `MEDIA_BASE_URL` |
| RN-IMG-03 | En la demo las imágenes se **versionan en el repo** y se sirven como estáticos del front. Subir desde el admin puede no persistir en hosting efímero (limitación documentada, D-155). Subir imágenes desde el admin solo se habilita en local (`MEDIA_UPLOAD_ENABLED=true`) |
| RN-IMG-04 | Se mantienen RN-CAT-07 (≤ 1 MB, formatos, **alt obligatorio**) |
| RN-IMG-05 | Imagen faltante → placeholder de marca |

## RN-TEN — Aislamiento por restaurante (multi-sucursal simulada, D-141)

| ID | Regla |
|---|---|
| RN-TEN-01 | Toda consulta y escritura se filtra por el `restaurantId` del actor (para un actor anónimo, el que fija RN-TEN-03); un recurso de otro restaurante responde `404` |
| RN-TEN-02 | Las pruebas de integración siembran **dos restaurantes** y verifican que nunca se cruzan datos |
| RN-TEN-03 | **Cómo se resuelve el restaurante en endpoints públicos** (D-144): (a) los endpoints con `tableCode` toman el restaurante **de la mesa**, nunca de un parámetro; (b) `GET /menu` con token usa el restaurante del token (si `restaurantId` difiere → `404`); sin token usa `restaurantId` y, si falta, el predeterminado del despliegue (`DEFAULT_RESTAURANT_ID`); un id desconocido → `404`; (c) el cliente nunca elige libremente de qué restaurante es un dato |

---
## Historial de cambios de este documento

| Fecha | Paso | Cambio | Decisión / hallazgo |
|---|---|---|---|
| 2026-10-11 | Paso 2 | RN-BIL-03 solo con `amountMinor > 0`; nuevas RN-ACC-14/15 (HOST en sesión abierta por el personal) y RN-TEN-03; RN-SES-06, RN-ORD-02/03/04, RN-BIL-05/06/07/09, RN-TIP-03/04/08, RN-ADJ-06, RN-CAT-08 e RN-IMG-03 alineadas; valores por defecto v0.2 agregados | D-142, D-143, D-144, D-146, D-137, D-153, D-155 · H-02, H-03, H-04, H-06 |
| 2026-10-11 | Paso 3 | RN-BIL-03 con mínimo dinámico `min(minPaymentMinor, balance)`; RN-SES-06 con reembolso automático si el saldo queda negativo (se quita la marca 🟡); RN-TIP-04 referencia la excepción | D-158, D-159 · H-13, H-14 |
| 2026-10-12 | Paso 9 (WP-BE-10) | RN-ADJ-04: la cortesía exige un ítem aceptado (un ítem `PENDING` no es un cargo todavía); la columna *Dónde* pasa de A a D porque la hace cumplir el dominio (`CourtesyRules`) | H-29 |
