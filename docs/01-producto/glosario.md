# Glosario y equivalencias español ↔ código

| Término (ES) | Identificador en código/API | Definición |
|---|---|---|
| Cafetería / local | `Restaurant` | Negocio. Hoy uno; todas las tablas llevan `restaurant_id` |
| Mesa | `DiningTable` (`dining_table`) | Lugar físico. Tiene un `tableCode` opaco para el QR |
| Sesión de mesa / visita | `TableSession` | Una visita concreta a una mesa. Agrupa pedidos y pagos. Nunca se reutiliza |
| Participante | `SessionParticipant` | Persona autorizada en una sesión (`HOST` o `MEMBER`) |
| Código de sesión | `joinCode` | 6 caracteres para sumarse a una sesión abierta |
| Token de invitado | `guestToken` | Credencial opaca y revocable atada a un participante |
| Pedido | `Order` (`customer_order`) | Solicitud enviada en un momento; contiene ítems |
| Ítem de pedido | `OrderItem` | Línea con producto, cantidad, precio histórico, estación y estado propios |
| Estación | `Station` | Sector de preparación (`BAR`, `KITCHEN`) |
| Estado de pedido | `OrderStatus` | `PENDING_REVIEW`, `REJECTED`, `CANCELLED`, `EXPIRED`, `ACCEPTED`, `IN_PREPARATION`, `READY`, `DELIVERED` |
| Estado de ítem | `ItemStatus` | `PENDING`, `QUEUED`, `IN_PREPARATION`, `READY`, `DELIVERED`, `CANCELLED` |
| Cuenta | `Bill` (calculada) | Cargos − pagos de una sesión. **No es una tabla**: se deriva |
| Cargo | `charge` | Suma de ítems no cancelados de pedidos aceptados |
| Pago | `Payment` | Registro de un pago simulado (o efectivo registrado) |
| Reembolso | `Payment(kind=REFUND)` | Devolución simulada |
| Saldo | `balanceMinor` | Cargos − pagos netos |
| Precio histórico / snapshot | `unitPriceMinor`, `productName` en `OrderItem` | Copia del nombre y precio al confirmar el pedido |
| Disponibilidad | `available` | Producto visible pero habilitado o no ("Agotado") |
| Archivado | `active=false` | Producto/categoría oculto, conservado por historial |
| Comprobante no fiscal | `Receipt` (PDF) | Documento informativo, no factura |
| Idempotencia | `Idempotency-Key` | Garantía de que repetir una solicitud no repite su efecto |
| Día de negocio | `businessDate` | Fecha operativa en zona del local (corte configurable) |
| Permiso | `Permission` | Acción autorizable, p. ej. `order.accept` |
| Rol | `Role` | Paquete de permisos |
| Auditoría | `AuditEvent` | Registro inmutable de acciones relevantes |
| Evento en tiempo real | `EV-*` | Pista de que algo cambió; el cliente reconsulta |
| Sesión HTTP de cuenta | `refreshToken` | Credencial de renovación para personal/registrados |
| MVP | — | Primera versión pequeña que demuestra el flujo principal |
| ADR | — | Registro de decisión de arquitectura |
| E2E | — | Prueba de extremo a extremo |

## Términos añadidos en v0.2
| Término (ES) | Identificador | Definición |
|---|---|---|
| Grupo de opciones | `OptionGroup` | Conjunto reutilizable de opciones ("Tamaño", "Leche") |
| Opción | `Option` | Elección con delta de precio (`priceDeltaMinor`) |
| Propina | `tipMinor` | Monto libre adjunto a un pago; no es parte de la cuenta |
| Ajuste | `BillAdjustment` | Descuento, cortesía o ajuste manual que reduce la cuenta |
| Cortesía | `COURTESY_ITEM` | Ítem sin cargo |
| Llamado al mozo | `WaiterCall` | Aviso del cliente al personal |
| Idioma | `locale` | `es-AR`, `en` (y `de` después) |
