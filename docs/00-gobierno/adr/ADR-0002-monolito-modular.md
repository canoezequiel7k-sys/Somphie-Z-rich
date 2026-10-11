# ADR-0002: Monolito modular y límites de módulos
**Estado:** propuesta

## Contexto
Un único desarrollador; transacciones críticas entre pedido, sesión y pago.

## Decisión
Un solo backend desplegable con módulos: `catalog`, `identity`, `floor`, `orders`, `preparation`, `billing`, `notifications`, `reporting`, `audit` (+ `shared`).
- **`orders` es dueño del agregado Pedido + Ítems y de todas sus transiciones** (incluidas las de preparación), porque el estado del pedido se deriva de sus ítems y debe recalcularse en la misma transacción.
- **`preparation`** expone las *colas por estación* (lectura) y los endpoints de acción por estación, delegando en `orders` mediante un puerto. Posee `Station` y la asignación personal↔estación.
- Entre módulos solo se usan **puertos de la capa de aplicación** o **eventos de dominio**; nunca repositorios ni entidades ajenas.
- `reporting` puede leer vistas/consultas SQL propias de solo lectura (excepción documentada).
- Las reglas se verifican con **ArchUnit** (ver `backend-arquitectura.md`).

## Alternativas
Microservicios (costo operativo sin beneficio), un `preparation` dueño de ítems (acopla transacciones entre módulos).

## Consecuencias
Un módulo grande podría extraerse luego. Hay que mantener disciplina de dependencias: las verifica la CI.
