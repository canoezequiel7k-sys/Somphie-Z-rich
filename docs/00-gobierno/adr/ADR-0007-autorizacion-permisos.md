# ADR-0007: Autorización por permisos
**Estado:** propuesta (D-021)

## Decisión
Modelo `Permission` (códigos tipo `order.accept`) agrupados en `Role` (datos, no código). Un usuario tiene roles y, si corresponde, **estaciones asignadas** (alcance de `item.prepare`). Cada caso de uso exige: (1) permiso, (2) alcance sobre el recurso (misma sesión, misma estación, mismo restaurante).
Roles de sistema sembrados: `OWNER`, `WAITER`, `BAR`, `KITCHEN`. Futuro: `MAITRE`, `SUPERVISOR` = nuevas filas.

## Consecuencias
La matriz vive en `01-producto/actores-y-roles.md` y se refleja en un seed Flyway y en tests parametrizados que verifican cada celda.
