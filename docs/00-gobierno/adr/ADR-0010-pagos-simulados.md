# ADR-0010: Pagos simulados
**Estado:** registro documental de decisiones existentes; no agrega ni re-aprueba decisiones.

## Contexto
WP-ARQ-70 y el listado de pendientes de [ADR-0009](ADR-0009-monorepo-git.md) solicitan registrar la arquitectura de pagos simulados. El comportamiento funcional ya está definido en decisiones, reglas y contrato; este documento reúne sus referencias sin sustituirlas.

## Decisión registrada
- [D-006 y D-125](../decisiones.md) prohíben dinero real/datos de tarjeta y definen el rechazo demo por centavos `.13`.
- [RN-BIL-05](../../02-dominio/reglas-negocio.md) precisa que solo se evalúa el monto principal, la propina no activa el rechazo y el pago rechazado queda `FAILED` sin alterar el saldo.
- RN-BIL-03/04 y D-146/D-147/D-158 ([reglas](../../02-dominio/reglas-negocio.md), [decisiones](../decisiones.md)) definen límites de monto, idempotencia, pago solo propina, cargos requeridos para propina y mínimo ajustado al saldo.
- [OpenAPI](../../04-api/openapi.yaml) define rutas, autenticación/permisos, requests/responses y errores; [`backend-arquitectura.md` §3 y §5](../../03-arquitectura/backend-arquitectura.md) define el puerto `PaymentProvider` y el adaptador `SimulatedPaymentProvider`.

## Motivos
La arquitectura aísla el mecanismo de pago detrás de un puerto, mientras las reglas de dominio y OpenAPI siguen siendo las fuentes canónicas del comportamiento. Registrar las referencias cierra el pendiente documental de Fase 7 sin duplicar ni ampliar esas reglas.

## Alternativas consideradas
No se agregan alternativas ni se infiere una decisión de proveedor externo: no están registradas en las fuentes y el alcance excluye pagos reales.

## Consecuencias y límites
La implementación debe cumplir las fuentes enlazadas y no introducir persistencia de tarjeta ni procesamiento real. Este ADR no define persistencia, reintentos ni tratamiento de fallos técnicos distintos del rechazo contractual; cualquier comportamiento adicional requiere respaldo en las fuentes normativas antes de implementarse.
