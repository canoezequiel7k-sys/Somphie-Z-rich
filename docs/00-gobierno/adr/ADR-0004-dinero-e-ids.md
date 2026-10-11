# ADR-0004: Dinero e identificadores
**Estado:** propuesta (D-101, D-102)

## Decisión
- Dinero: `BIGINT` en centavos (`*_minor`), moneda `ARS` en settings. Sin decimales flotantes en ningún lado. La UI formatea con `Intl.NumberFormat('es-AR')`.
- IDs públicos: UUID (preferible v7 si la librería lo permite sin fricción; si no, v4). Nunca secuenciales expuestos.
- `display_code` de pedido: `#NNN` por día de negocio (`order_counter`), solo para humanos.
- Fechas: `timestamptz` en UTC; API en ISO-8601 con `Z`; "día de negocio" calculado en `America/Argentina/Buenos_Aires` con corte configurable (por defecto 04:00).

## Consecuencias
Todas las capas hablan centavos. Divisiones (dividir cuenta) se resuelven en UI con resto asignado al primero.
