# Casos límite

Cada fila es un escenario **que debe tener una prueba**. Columnas: mecanismo que lo resuelve, resultado esperado y relación con casos de uso/reglas/errores. QA convierte cada `CL-nnn` en un test con ese mismo ID en el nombre.

## 1. Red y reintentos

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-001 | El cliente envía un pedido, la respuesta se pierde y reintenta con la **misma** `Idempotency-Key` | Se devuelve el mismo pedido; no se crea otro; header `Idempotent-Replayed: true` | `idempotency_record` + unique | CU-07, RN-ORD-17 |
| CL-002 | Doble toque en "Enviar pedido" | Un solo pedido; botón deshabilitado mientras se envía (UI) y clave idempotente (servidor) | UI + servidor | CU-07 |
| CL-003 | Dos solicitudes con la misma clave **al mismo tiempo** (aún en proceso) | La segunda espera o recibe `409 IDEMPOTENCY_IN_PROGRESS`; nunca dos efectos | Lock por clave | ADR-0005 |
| CL-004 | Misma clave con **cuerpo distinto** | `422 IDEMPOTENCY_KEY_REUSED`; no se ejecuta | Hash del request | — |
| CL-005 | Pago: respuesta perdida y reintento con misma clave | Un solo pago contabilizado | Idempotencia | CU-12, RN-BIL-04 |
| CL-006 | Cliente sin conexión al abrir la web con carrito guardado | Muestra carrito y menú en caché con aviso "sin conexión"; no permite enviar | SW/caché + UI | CU-05/06 |
| CL-007 | La conexión vuelve y el cliente reintenta tras minutos | Si la sesión ya cerró → `SESSION_CLOSED`; si no, procede | Validación servidor | — |
| CL-008 | Android pierde red mientras el mozo toca "Aceptar" | La UI queda en "procesando"; al reintentar con la misma clave el resultado es consistente (aceptado una vez) | Idempotencia | CU-22 |
| CL-009 | El servidor responde 500 después de haber hecho commit | Reintento con misma clave devuelve el resultado guardado | Registro idempotente en la misma tx | ADR-0005 |
| CL-010 | Timeout del cliente pero el servidor sigue procesando | Igual que CL-003 | — | — |

## 2. Concurrencia

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-020 | Dos empleados aceptan el **mismo** pedido a la vez | Uno gana; el otro `409 ORDER_ALREADY_DECIDED` con quién/cuándo; una sola auditoría de aceptación | `UPDATE … WHERE status='PENDING_REVIEW'` | CU-22 |
| CL-021 | Un empleado acepta y otro rechaza a la vez | Gana el primero en commit; el otro recibe `ORDER_ALREADY_DECIDED` | ídem | CU-22/23 |
| CL-022 | El cliente cancela justo cuando el personal acepta | Una sola operación efectiva; la otra `ORDER_NOT_CANCELLABLE` / `ORDER_ALREADY_DECIDED` | ídem | CU-09 |
| CL-023 | Dos participantes pagan a la vez y juntos exceden el saldo | Se serializan; el segundo que exceda recibe `PAYMENT_EXCEEDS_BALANCE` con saldo actual | `FOR UPDATE` sesión | CU-12 |
| CL-024 | Dos pagos **intencionales** del mismo monto con claves distintas | Ambos válidos si el saldo alcanza | — | RN-BIL-04 |
| CL-025 | Un participante envía pedido mientras el personal cierra la sesión | Gana el primero: si cierra primero → `SESSION_CLOSED`; si el pedido entra primero → el cierre falla por `SESSION_HAS_PENDING_ORDERS` | `FOR SHARE` vs `FOR UPDATE` | CU-07/34 |
| CL-026 | Se paga mientras se cancela un ítem (cambia el saldo) | Se serializa; el saldo final es coherente; pago nunca excede el saldo vigente | locks de sesión | CU-12/27 |
| CL-027 | Dos personas escanean la misma mesa libre simultáneamente | Una abre sesión; la otra `SESSION_ALREADY_OPEN` y pasa a unirse | Índice único parcial | RN-ACC-13 |
| CL-028 | Barra y Cocina marcan listos ítems del mismo pedido a la vez | Ambos se aplican; el estado derivado del pedido es correcto | Recalculo en tx; lock de pedido | RN-ORD-14 |
| CL-029 | Dos personas cambian el mismo ítem (`start`) a la vez | Una gana; la otra `ITEM_INVALID_TRANSITION` | Condicional por estado | RN-PRE-05 |
| CL-030 | Dos instancias del job de expiración corren a la vez | Cada pedido expira una sola vez; la actualización condicional no modifica pedidos ya decididos | Condicional por estado | CU-50 |
| CL-031 | Edición simultánea del mismo producto en el admin | El segundo guardado falla con `OPTIMISTIC_LOCK_FAILED` y recarga | `@Version` | CU-40 |
| CL-032 | Número `#NNN` asignado a dos pedidos simultáneos | Nunca se repite | `UPDATE … RETURNING` en `order_counter` | RN-ORD-15 |

## 3. QR, sesión y participantes

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-040 | QR de una mesa regenerada | `TABLE_CODE_INVALID` con mensaje amable | — | RN-ACC-03 |
| CL-041 | Escanea una mesa con sesión abierta sin código | `JOIN_CODE_REQUIRED` | — | RN-ACC-06 |
| CL-042 | Fuerza bruta del código | Tras 5 fallos bloqueo 10 min (`JOIN_CODE_RATE_LIMITED`); respuesta no revela cuántos faltan | Rate limit | RN-ACC-07 |
| CL-043 | Alguien fotografía el QR y pide desde otra ciudad (mesa libre) | Puede abrir sesión pero **nada se prepara** sin aceptación humana; límites de pendientes, expiración 15 min, y sesión vacía expira; opción `requireStaffToOpenSession` | Control humano + límites | ADR-0003 |
| CL-044 | Participante revocado intenta pedir | `SESSION_ACCESS_REVOKED`; sus pedidos ya enviados permanecen | Revocación inmediata | RN-ACC-11 |
| CL-045 | La sesión se cierra y el cliente abre la web con su token | Pantalla "Visita finalizada" + comprobante (hasta 24 h) | Token solo lectura | RN-SES-07 |
| CL-046 | Pasan 24 h tras el cierre | `TOKEN_EXPIRED`; vuelve a CU-01 | — | RN-ACC-08 |
| CL-047 | Nueva visita en la misma mesa | Sesión nueva sin pedidos ni saldo previos; el token viejo **no** sirve | Sesión nueva | RN-SES-02 |
| CL-048 | Mesa desactivada con sesión abierta | Se rechaza desactivar (`TABLE_HAS_OPEN_SESSION`) | — | RN-SES-10 |
| CL-049 | Sesión llena (12) | `SESSION_FULL` | — | RN-ACC-09 |
| CL-050 | Host intenta revocarse | `HOST_CANNOT_BE_REVOKED` | — | RN-ACC-10 |
| CL-051 | Mismo cliente abre la web desde dos dispositivos | Se une dos veces (dos participantes) o reutiliza token si lo copia; no se mezclan | — | — |
| CL-052 | Nombre con HTML/emoji extremo | Se sanitiza/escapa; longitud ≤ 30 | Validación | RN-ACC-12 |
| CL-053 | Mesa abierta por el personal pero nadie se une | La sesión vacía expira a los 30 min | Job | RN-SES-08 |
| CL-054 | Se rota el código y alguien tiene el viejo | El viejo falla con `JOIN_CODE_INVALID`; los ya unidos siguen | — | RN-ACC-07 |
| CL-055 | Sesión abierta por el personal: se une la primera persona | Pasa a ser `HOST`; los que se unen después son `MEMBER` | Rol decidido al unirse | RN-ACC-14, CU-03/30 |
| CL-056 | Dos personas se unen **a la vez** a una sesión sin HOST | Exactamente un `HOST` y un `MEMBER`; ninguna recibe error | Índice único parcial + `INSERT … ON CONFLICT DO NOTHING` | RN-ACC-15, D-143 |

## 4. Pedidos y catálogo

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-060 | El precio cambia entre armar el carrito y enviar | `409 PRICE_CHANGED` con precios actuales; el cliente reconfirma; **no** se cobra el precio viejo | `acknowledgedTotalMinor` | RN-ORD-03 |
| CL-061 | Un producto se agota entre carrito y envío | `409 PRODUCT_UNAVAILABLE` con ids | — | CU-07 |
| CL-062 | Un producto se agota **después** de enviar y antes de aceptar | Personal lo ve (`productCurrentlyAvailable=false`) y acepta **excluyendo** o rechaza | RN-ORD-16 | CU-22 |
| CL-063 | El cliente manipula el JSON enviando `unitPriceMinor` | Se ignora/campo no existe en contrato; el servidor usa catálogo | Contrato | RN-ORD-03 |
| CL-064 | Cantidad 0, negativa o 10 000 | `VALIDATION_FAILED` / `ORDER_QUANTITY_EXCEEDED` | Validación | RN-ORD-02 |
| CL-065 | Pedido vacío | `ORDER_EMPTY` | — | — |
| CL-066 | Más de 3 pedidos pendientes | `ORDER_PENDING_LIMIT_REACHED` | — | RN-ORD-06 |
| CL-067 | Pedido fuera de horario | `RESTAURANT_CLOSED` | Settings | RN-ORD-07 |
| CL-068 | Se archiva un producto con pedidos históricos | No se borra; pedidos viejos conservan nombre/precio | Snapshot | RN-CAT-02 |
| CL-069 | Se cambia el nombre del producto | Pedidos viejos muestran el nombre original | Snapshot | RN-ORD-04 |
| CL-070 | El pedido queda sin decisión al cumplirse 15 min | El job puede cambiarlo a `EXPIRED`; cliente notificado y puede reenviar | Job; límite inclusivo | RN-ORD-12 |
| CL-071 | El personal acepta un pedido cuyo estado ya es `EXPIRED` | `ORDER_EXPIRED`; si solo superó el TTL pero sigue `PENDING_REVIEW`, puede aceptarse hasta que el job lo expire | Estado actual; el job decide la expiración | CU-22, H-26 |
| CL-072 | Se aceptan todos los ítems excepto uno (excluido) | Pedido `ACCEPTED`; el ítem queda `CANCELLED`; el cliente ve el aviso; no se cobra | RN-ORD-10 | — |
| CL-073 | Se intenta excluir todos los ítems al aceptar | `ORDER_ALL_ITEMS_EXCLUDED` | — | — |
| CL-074 | Nota de ítem de 5 000 caracteres | `VALIDATION_FAILED` | — | — |
| CL-075 | Pedido de producto de **otra** estación mezclado | Se separan ítems por estación automáticamente | Snapshot de estación | RN-PRE-01 |
| CL-076 | Menú con ETag viejo | `304` si no cambió; si cambió, `200` con nuevo `catalogVersion` | ETag | RN-CAT-05 |
| CL-077 | Dos productos con el mismo nombre en distinta categoría | Permitido; en la misma categoría no (`PRODUCT_NAME_DUPLICATE`) | Unique | RN-CAT-01 |

## 5. Preparación

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-080 | Barra termina bebidas, cocina aún no | Pedido sigue `IN_PREPARATION`; nadie puede marcarlo completo | Derivación | §4 estados |
| CL-081 | Todos listos pero el mozo solo entrega parte | Pedido `READY` hasta que se entregue todo | — | — |
| CL-082 | Se cancela el único ítem pendiente de un pedido | Si los demás están entregados, pedido pasa a `DELIVERED`; si todos cancelados → `CANCELLED` | Derivación | RN-ORD-14 |
| CL-083 | Cocina intenta marcar un ítem de barra | `ITEM_STATION_FORBIDDEN` | — | RN-PRE-02 |
| CL-084 | Deshacer entrega pasados 5 min | `ITEM_UNDO_WINDOW_EXPIRED` | — | RN-PRE-09 |
| CL-085 | Marcar listo un ítem cuya sesión ya cerró | `SESSION_CLOSED` | — | RN-PRE-03 |
| CL-086 | Ítem esperando más del SLA | Aparece `delayed=true` y resaltado | — | RN-PRE-08 |
| CL-087 | Saltar de `QUEUED` a `READY` | Permitido (atajo) | — | §3 estados |
| CL-088 | Intentar `DELIVERED` desde `QUEUED` | `ITEM_INVALID_TRANSITION` | — | — |

## 6. Cuenta, pagos y cierre

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-100 | Pagar más que el saldo | `PAYMENT_EXCEEDS_BALANCE` | — | RN-BIL-03 |
| CL-101 | Pagar con saldo 0 (monto principal > 0) | `PAYMENT_NOTHING_TO_PAY`. Un pago **solo propina** con saldo 0 sí se acepta (CL-182) | — | RN-BIL-03, D-146 |
| CL-102 | Monto principal negativo, con decimales o menor al mínimo `min(minPaymentMinor, saldo)` | `PAYMENT_AMOUNT_INVALID` / `VALIDATION_FAILED`. (Monto 0 sin propina: CL-180) | Enteros | RN-BIL-03, D-158 |
| CL-103 | Pagar después de cerrada la sesión | `SESSION_CLOSED` | — | RN-BIL-08 |
| CL-104 | Se paga todo y luego se pide algo más | Saldo vuelve a positivo; se puede pagar otra vez | — | — |
| CL-105 | Se cancela un ítem ya pagado → saldo negativo | Mesa en "saldo a favor"; el cierre falla (`SESSION_BALANCE_PENDING`) hasta registrar reembolso | RN-BIL-07 | — |
| CL-106 | Reembolso mayor a lo pagado | `REFUND_EXCEEDS_PAID` | — | — |
| CL-107 | Anular un pago ya anulado | `PAYMENT_NOT_VOIDABLE` | — | — |
| CL-108 | Pago rechazado en demo (`.13`) | Registro `FAILED`; saldo sin cambios; mensaje claro | — | RN-BIL-05 |
| CL-109 | Cierre con pedido pendiente | `SESSION_HAS_PENDING_ORDERS` con lista | — | RN-SES-05 |
| CL-110 | Cierre con ítems sin entregar | `SESSION_HAS_ACTIVE_ITEMS` con lista | — | — |
| CL-111 | Cierre con saldo > 0 | `SESSION_BALANCE_PENDING` con monto | — | — |
| CL-112 | Cierre forzado con deuda | Permitido a `OWNER` con motivo; el saldo queda como **no cobrado** (auditoría y comprobante) y los pagos y propinas se conservan (D-159); comprobante marca "cierre forzado" | — | RN-SES-06 |
| CL-113 | Cerrar una sesión ya cerrada | Idempotente: `200` con estado actual | — | CU-34 |
| CL-114 | Cambia el precio del catálogo con la cuenta abierta | La cuenta **no cambia** | Snapshot | RN-BIL-01 |
| CL-115 | División en partes iguales con resto (ej. $1.000 / 3) | UI sugiere 334/333/333 (resto al primero); servidor solo ve montos | UI | RN-BIL-10 |
| CL-116 | Comprobante pedido antes del cierre | `RECEIPT_NOT_AVAILABLE` | — | RN-BIL-09 |
| CL-117 | Visita vacía (nadie pidió) | Staff cierra como `EMPTY` o expira `EXPIRED_EMPTY` | — | RN-SES-04 |
| CL-118 | Quedan 50 centavos de saldo (menos que el mínimo de 100) | Un pago de exactamente 50 se acepta; 49 → `PAYMENT_AMOUNT_INVALID`; 51 → `PAYMENT_EXCEEDS_BALANCE` | Mínimo dinámico | RN-BIL-03, D-158 |
| CL-119 | Cierre forzado cuando, tras cancelar ítems, los pagos superan los cargos | En la misma transacción se registra un reembolso simulado automático por el exceso (sin propina), auditado; la sesión cierra sin saldo negativo | Transacción única | RN-SES-06, D-159 |

## 7. Identidad y seguridad

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-120 | Login con email inexistente vs contraseña incorrecta | Misma respuesta y tiempo similar | Anti-enumeración | RN-IDN-03 |
| CL-121 | Fuerza bruta de login | Bloqueo temporal | Rate limit | RN-IDN-04 |
| CL-122 | Refresh token reutilizado | Se revoca toda la familia; debe loguear de nuevo | Rotación | RN-IDN-05 |
| CL-123 | Usuario deshabilitado con token vigente | Rechazo inmediato | `tokenVersion` | RN-IDN-06 |
| CL-124 | Personal de Cocina llama endpoint de aceptar | `403 FORBIDDEN` aunque la UI no muestre el botón | Permisos servidor | ADR-0007 |
| CL-125 | Participante intenta leer pedido de otra sesión (IDOR) | `404 ORDER_NOT_FOUND` | Alcance por sesión | actores §4 |
| CL-126 | El dueño intenta quitarse el rol de último dueño | `LAST_OWNER_PROTECTED` | — | RN-IDN-07 |
| CL-127 | Token en query o en logs | Nunca; cabecera `Authorization`; los logs enmascaran tokens | Config | seguridad |
| CL-128 | Inyección en nombre/nota (XSS) | Se escapa al renderizar; sin `dangerouslySetInnerHTML` | UI + validación | — |
| CL-129 | CORS desde origen no autorizado | Bloqueado | Config | seguridad |
| CL-130 | Respuesta de error con traza interna | Nunca; solo `code`, `title`, `traceId` | Manejador global | convenciones |

## 8. Tiempo real y dispositivos

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-140 | Se cae SSE | Cliente cae a polling y reintenta con backoff exponencial + jitter | RN-RT-05 | — |
| CL-141 | Evento duplicado | Se ignora por `eventId` | RN-RT-03 | — |
| CL-142 | Evento fuera de orden / atrasado | Se compara `version`; si es menor al conocido se ignora; ante duda se reconsulta | — | — |
| CL-143 | `Last-Event-ID` demasiado viejo | `410 EVENT_ID_TOO_OLD` → refresco completo | — | tiempo-real |
| CL-144 | Android en segundo plano | No hay stream; al volver a primer plano reconsulta (push FCM es posterior) | — | — |
| CL-145 | Navegador cerrado y el pedido se acepta | Al reabrir, `GET /sessions/current` muestra el estado real | Backend fuente de verdad | RN-RT-01 |
| CL-146 | Reloj del dispositivo desfasado | La UI no calcula expiraciones con su reloj; usa tiempos del servidor | — | — |
| CL-147 | Zona horaria distinta del dispositivo | Fechas se muestran en zona del local para reportes; en UI cliente en su zona | — | ADR-0004 |
| CL-148 | Muchas pestañas abiertas | Cada una consulta con el mismo token; sin duplicar efectos | — | — |

## 9. Infra y operación

| ID | Escenario | Comportamiento esperado | Mecanismo | Refs |
|---|---|---|---|---|
| CL-160 | Hosting gratuito "duerme" el backend | Primera petición lenta: la web muestra estado de carga con mensaje; no se pierde nada | Skeleton/retry | nube.md |
| CL-161 | La base está al límite de conexiones | `503 SERVICE_UNAVAILABLE` con reintento sugerido; pool acotado | HikariCP | — |
| CL-162 | Falla el envío de email | La operación de negocio queda guardada; se registra el fallo | RN-NOT-01 | — |
| CL-163 | Migración Flyway falla al arrancar | La app no arranca (fail fast); no queda base a medias | Flyway | — |
| CL-164 | Reinicio del servidor durante una operación | Transacción atómica: todo o nada; idempotencia cubre reintentos | — | — |
| CL-165 | Restauración de backup | Procedimiento documentado y probado al menos una vez | nube.md | — |

---
# Casos límite añadidos en v0.2

## 10. Opciones y modificadores
| ID | Escenario | Comportamiento esperado | Refs |
|---|---|---|---|
| CL-170 | Grupo obligatorio sin elegir | `OPTION_SELECTION_INVALID` (con grupo, min y max) | RN-OPT-03 |
| CL-171 | Opción que pertenece a otro producto | `OPTION_NOT_FOUND` | RN-OPT-03 |
| CL-172 | Una opción se agota entre carrito y envío | `OPTION_UNAVAILABLE`; el carrito la marca | RN-OPT-03 |
| CL-173 | Cambia el precio de una opción | `PRICE_CHANGED` con precios vigentes | RN-OPT-07 |
| CL-174 | Grupo `SINGLE` con dos opciones | `OPTION_SELECTION_INVALID` | RN-OPT-03 |
| CL-175 | Se archiva una opción con pedidos históricos | Los pedidos conservan su snapshot | RN-OPT-11 |
| CL-176 | Grupo obligatorio sin opciones disponibles | El producto aparece como "Agotado" | RN-OPT-10 |
| CL-177 | Mismo producto con distintas opciones | Líneas separadas; la cola muestra las opciones de cada una | RN-OPT-05 |
| CL-178 | Más de 10 opciones en una línea | `VALIDATION_FAILED` | RN-OPT-06 |

## 11. Propina
| ID | Escenario | Comportamiento esperado | Refs |
|---|---|---|---|
| CL-180 | Pago con monto 0 y propina 0 | `TIP_INVALID` | RN-TIP-04 |
| CL-181 | Propina desmedida por error de tipeo | `TIP_EXCEEDS_LIMIT` con tope | RN-TIP-03 |
| CL-182 | Pago solo propina con saldo 0 y sesión abierta | Permitido | RN-TIP-01 |
| CL-183 | Monto principal excede el saldo y trae propina | `PAYMENT_EXCEEDS_BALANCE`; **no** se registra la propina | RN-TIP-09 |
| CL-184 | Se anula un pago con propina | La propina también se anula | RN-TIP-05 |
| CL-185 | Propina en sesión cerrada | `SESSION_CLOSED` | RN-TIP-06 |
| CL-186 | Propina sin cargos | `TIP_NOT_ALLOWED` | RN-TIP-03 |
| CL-187 | Reintento con misma clave de un pago con propina | Un solo pago y una sola propina | RN-TIP-09 |

## 12. Descuentos, cortesías y ajustes
| ID | Escenario | Comportamiento esperado | Refs |
|---|---|---|---|
| CL-190 | Mozo aplica más del 15 % de los cargos brutos | `ADJUSTMENT_LIMIT_EXCEEDED` | RN-ADJ-06 |
| CL-191 | Descuento mayor al saldo | `ADJUSTMENT_EXCEEDS_BALANCE` | RN-ADJ-05 |
| CL-192 | Llega un pedido nuevo después del descuento | No se descuenta (importe congelado) | RN-ADJ-03 |
| CL-193 | Se cancela un ítem con cortesía | La cortesía se anula en la misma transacción | RN-ADJ-08 |
| CL-194 | Cortesía de un ítem cancelado, aún sin aceptar (`PENDING`) o ya con cortesía | `ADJUSTMENT_ITEM_INVALID` | RN-ADJ-04 |
| CL-195 | Pago y descuento simultáneos | Se serializan; el saldo nunca queda negativo por el ajuste | RN-ADJ-09 |
| CL-196 | Se anula un ajuste con la cuenta ya pagada | Permitido: el saldo vuelve a subir | RN-ADJ-07 |
| CL-197 | Mozo intenta anular el ajuste de otro o pasados 10 min | `FORBIDDEN` / `ADJUSTMENT_NOT_VOIDABLE` | RN-ADJ-07 |
| CL-198 | Ajuste con reintento (misma clave) | Un solo ajuste | RN-ADJ-09 |
| CL-199 | Mozo aplica varios descuentos chicos que **suman** más del 15 % de los cargos brutos | El que supera el acumulado recibe `ADJUSTMENT_LIMIT_EXCEEDED`; los anteriores quedan | RN-ADJ-06, D-142 |

## 13. Llamado al mozo
| ID | Escenario | Comportamiento esperado | Refs |
|---|---|---|---|
| CL-200 | Doble toque en "Llamar al mozo" | Un solo llamado | RN-WCL-02/04 |
| CL-201 | Llamar con sesión cerrada | `SESSION_CLOSED` | RN-WCL-04 |
| CL-202 | Spam de llamados | `WAITER_CALL_ALREADY_ACTIVE` / `WAITER_CALL_COOLDOWN` | RN-WCL-02 |
| CL-203 | Nadie atiende en 30 min | `EXPIRED` | RN-WCL-03 |
| CL-204 | Pedir la cuenta con saldo 0 | Permitido (sirve para cerrar) | RN-WCL-06 |

## 14. Idiomas, imágenes y aislamiento
| ID | Escenario | Comportamiento esperado | Refs |
|---|---|---|---|
| CL-210 | Producto sin traducción al idioma elegido | Fallback a `es-AR` | RN-I18N-03 |
| CL-211 | Pedido hecho en inglés | El personal lo ve en español; el cliente en inglés | RN-I18N-05 |
| CL-212 | Cambia el idioma con el carrito lleno | El carrito se conserva (por `productId`) | RN-I18N-07 |
| CL-213 | `Accept-Language` inválido | Fallback a `es-AR` | RN-I18N-04 |
| CL-214 | Imagen inexistente | Placeholder de marca | RN-IMG-05 |
| CL-215 | Usuario del restaurante A pide un recurso del B | `404` | RN-TEN-01 |
| CL-216 | `GET /menu` sin token y sin `restaurantId` | Devuelve el menú del restaurante predeterminado del despliegue | RN-TEN-03, D-144 |
| CL-217 | `GET /menu` con `restaurantId` desconocido, o distinto del de su token | `404` | RN-TEN-03, D-144 |
