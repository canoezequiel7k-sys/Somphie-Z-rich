# Actores, roles y permisos

> Principio (D-021, ADR-0007): **los permisos son la unidad de autorización; los roles son paquetes de permisos guardados como datos.** La interfaz oculta lo no permitido, pero **el backend valida siempre** (permiso + alcance sobre el recurso).

## 1. Actores

| Actor | Autenticación | Alcance |
|---|---|---|
| **Visitante anónimo** | Ninguna | Solo menú público y resolución de QR |
| **Invitado (participante)** | Token de invitado opaco (ADR-0003) | Solo **su sesión** |
| **Cliente registrado** | Email + contraseña (JWT) + token de sesión cuando participa | Su sesión + extras (historial, comprobantes) |
| **Personal** (Mozo, Barra, Cocina) | Email + contraseña (JWT) | Operación del local; Cocina/Barra limitadas por **estación** |
| **Dueño** | Email + contraseña (JWT) | Todo el local |
| **Sistema** (jobs) | Interno | Expirar pedidos y sesiones, emitir eventos |

> Maître y Supervisor: **no existen en el MVP**. Se crean luego como roles nuevos (datos) sin cambiar el modelo.

## 2. Catálogo de permisos

| Permiso | Qué autoriza | Alcance |
|---|---|---|
| `menu.read` | Ver menú público | Público |
| `session.open` / `session.join` | Abrir o unirse a sesión por QR | Mesa válida |
| `order.create` | Enviar pedido | Sesión propia abierta |
| `order.cancel_own` | Cancelar pedido propio pendiente | Autor del pedido o `HOST` |
| `bill.read` | Ver cuenta | Sesión propia |
| `payment.create` | Registrar pago simulado | Sesión propia abierta |
| `participant.manage` | Revocar participantes, rotar código | Solo `HOST` de la sesión |
| `order.read` | Ver pedidos | Restaurante; Cocina solo ítems de su estación aceptados |
| `order.accept` / `order.reject` | Decidir pedido pendiente | Restaurante |
| `order.cancel` | Cancelar ítems/pedido ya aceptado | Restaurante (estación propia si es Cocina/Barra) |
| `order.create_on_behalf` | Tomar pedido a nombre del cliente | Sesión abierta |
| `item.prepare` | Cambiar estado en preparación | **Estaciones asignadas** al usuario |
| `item.deliver` | Marcar entregado | Restaurante |
| `item.undo` | Revertir transición reciente | Igual alcance que la acción original |
| `session.read` | Ver detalle de sesión/mesa | Restaurante |
| `session.open_manual` | Abrir sesión desde el personal | Mesa activa |
| `session.close` | Cerrar sesión (con reglas) | Restaurante |
| `session.force_close` | Cierre forzado con motivo | Restaurante |
| `participant.revoke` | Revocar participante / rotar código | Restaurante |
| `payment.register_cash` | Registrar pago en efectivo | Sesión abierta |
| `payment.void` | Anular un pago | Sesión abierta |
| `payment.refund` | Registrar reembolso simulado | Sesión abierta |
| `availability.toggle` | Activar/desactivar disponibilidad de producto | Restaurante |
| `table.read` | Ver tablero de mesas | Restaurante |
| `catalog.manage` | CRUD categorías/productos/precios/imágenes | Restaurante |
| `table.manage` | CRUD mesas, regenerar QR | Restaurante |
| `user.manage` | Alta/baja/edición de usuarios, asignar roles y estaciones | Restaurante |
| `role.manage` | Editar paquetes de permisos (**futuro**; en MVP roles de sistema de solo lectura) | Restaurante |
| `report.read` / `report.export` | Reportes y PDF | Restaurante |
| `audit.read` | Consultar auditoría | Restaurante |
| `settings.manage` | Horarios, límites, textos | Restaurante |

## 3. Matriz rol × permiso (por defecto — 🟡 validar D-115, D-116)

✔ = permitido · ○ = permitido con alcance restringido · — = no

| Permiso | Invitado | Registrado | Mozo | Barra | Cocina | Dueño |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| `menu.read` | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `session.open` / `session.join` | ✔ | ✔ | — | — | — | — |
| `order.create` | ✔ | ✔ | — | — | — | — |
| `order.cancel_own` | ○ | ○ | — | — | — | — |
| `bill.read` | ○ | ○ | — | — | — | — |
| `payment.create` | ✔ | ✔ | — | — | — | — |
| `participant.manage` | ○ host | ○ host | — | — | — | — |
| `order.read` | — | — | ✔ | ✔ | ○ | ✔ |
| `order.accept` | — | — | ✔ | ✔ | — | ✔ |
| `order.reject` | — | — | ✔ | ✔ | — | ✔ |
| `order.cancel` | — | — | ✔ | ✔ | ○ | ✔ |
| `order.create_on_behalf` | — | — | ✔ | ✔ | — | ✔ |
| `item.prepare` | — | — | — | ○ BAR | ○ KITCHEN | ✔ |
| `item.deliver` | — | — | ✔ | ✔ | — | ✔ |
| `item.undo` | — | — | ○ | ○ | ○ | ✔ |
| `session.read` | — | — | ✔ | ✔ | — | ✔ |
| `session.open_manual` | — | — | ✔ | — | — | ✔ |
| `session.close` | — | — | ✔ | — | — | ✔ |
| `session.force_close` | — | — | — | — | — | ✔ |
| `participant.revoke` | — | — | ✔ | — | — | ✔ |
| `payment.register_cash` | — | — | ✔ | ✔ | — | ✔ |
| `payment.void` | — | — | ✔ | — | — | ✔ |
| `payment.refund` | — | — | ✔ | — | — | ✔ |
| `availability.toggle` | — | — | ✔ | ✔ | ✔ | ✔ |
| `table.read` | — | — | ✔ | ✔ | — | ✔ |
| `catalog.manage` / `table.manage` / `user.manage` / `settings.manage` | — | — | — | — | — | ✔ |
| `report.read` / `report.export` / `audit.read` | — | — | — | — | — | ✔ |

**Nota Cocina:** ve nombre de mesa y notas de los ítems de su estación, **no** datos de participantes ni cuenta.

## 4. Reglas de alcance (cómo se valida cada permiso)

| Tipo | Regla | Error si falla |
|---|---|---|
| **Sesión propia** | El token pertenece a un participante **no revocado** de esa sesión | `SESSION_ACCESS_REVOKED` / `FORBIDDEN` |
| **Estación** | `item.stationId ∈ user.stationIds` (o rol `OWNER`) | `ITEM_STATION_FORBIDDEN` |
| **Restaurante** | `resource.restaurantId == actor.restaurantId` (hoy único, pero se comprueba) | `RESOURCE_NOT_FOUND` (no revelar existencia) |
| **Host** | `participant.role == HOST` | `FORBIDDEN` |
| **Autor del pedido** | `order.createdByParticipantId == actor.participantId` o actor es host | `FORBIDDEN` |

> Responder `404` (y no `403`) cuando el recurso pertenece a otra sesión/restaurante, para no filtrar existencia.

## 5. Qué ve cada rol en cada app

| Rol | Web cliente | App Android (pestañas) | Panel `/admin` |
|---|---|---|---|
| Invitado / Registrado | Todas las pantallas SC-C | — | — |
| Mozo | — | Entrantes · Mesas · Disponibilidad · Más | — |
| Barra | — | Entrantes · **Estación Barra** · Mesas · Disponibilidad | — |
| Cocina | — | **Estación Cocina** · Disponibilidad | — |
| Dueño | — | Todas | Todo |

> La app Android arma sus pestañas desde la lista de **permisos que devuelve `GET /me`**, no desde el nombre del rol. Así un rol futuro funciona sin cambiar la app.

## 6. Invariantes de seguridad de usuarios
- El personal **no** se auto-registra: lo crea el dueño.
- Siempre debe existir al menos un `OWNER` activo (`LAST_OWNER_PROTECTED`).
- Deshabilitar un usuario invalida sus tokens de inmediato (`tokenVersion`).
- Cambiar roles/estaciones queda en auditoría.

## 7. Permisos añadidos en v0.2

| Permiso | Qué autoriza | Alcance |
|---|---|---|
| `adjustment.apply` | Aplicar descuentos, cortesías y ajustes **dentro del límite** (`maxStaffDiscountPercent`, `maxStaffAdjustmentMinor`) | Sesión abierta |
| `adjustment.apply_any` | Aplicar sin límite | Sesión abierta |
| `adjustment.void` | Anular ajustes (Mozo: propios y ≤ 10 min; Dueño: cualquiera) | Sesión abierta |
| `waiter_call.create` | Llamar al mozo | Sesión propia abierta |
| `waiter_call.handle` | Ver, tomar y resolver llamados | Restaurante |
| `availability.toggle` | (amplía) también alterna **opciones** | Restaurante |
| `payment.create` / `payment.register_cash` | (amplían) pueden incluir propina | Sesión abierta |

| Permiso | Invitado | Registrado | Mozo | Barra | Cocina | Dueño |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| `adjustment.apply` | — | — | ○ límite | — | — | ✔ |
| `adjustment.apply_any` | — | — | — | — | — | ✔ |
| `adjustment.void` | — | — | ○ propios | — | — | ✔ |
| `waiter_call.create` | ✔ | ✔ | — | — | — | — |
| `waiter_call.handle` | — | — | ✔ | ✔ | — | ✔ |

Límite de descuento del Mozo: 10 % (🟡 Q-17). Cocina no ve propinas ni ajustes.
