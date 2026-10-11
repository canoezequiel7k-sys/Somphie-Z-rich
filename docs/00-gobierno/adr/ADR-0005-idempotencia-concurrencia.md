# ADR-0005: Idempotencia y concurrencia
**Estado:** propuesta. Detalle completo: `03-arquitectura/idempotencia-concurrencia.md`

## Decisión
- Header `Idempotency-Key` (UUID) obligatorio en operaciones no naturalmente idempotentes (crear pedido, pagar, reembolsar, aceptar/rechazar…). Registro persistente con hash del cuerpo y respuesta guardada.
- Transiciones de estado con **actualización condicional** (`WHERE status = :esperado`) o `@Version`; el perdedor recibe 409 con el estado actual.
- Bloqueo de fila de sesión: `FOR SHARE` para operaciones que cambian cargos; `FOR UPDATE` para pagos, reembolsos y cierre.
- Restricciones de base de datos como última defensa (índice único parcial: una sesión `OPEN` por mesa).

## Consecuencias
Pruebas de concurrencia reales con Testcontainers son obligatorias para estos casos.
