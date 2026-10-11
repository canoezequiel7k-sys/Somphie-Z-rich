# ADR-0003: Identificación por QR y acceso a sesión
**Estado:** propuesta (base: D-022, D-105, D-117)

## Contexto
Un QR estático no prueba presencia física. No debe contener secretos ni exponer cuentas.

## Decisión
- QR = URL `https://<web>/t/<tableCode>`; `tableCode` aleatorio opaco (≥128 bits, base62), regenerable por el dueño.
- Primer escaneo sin sesión abierta → abre sesión, crea participante `HOST` y emite **token de invitado**.
- Escaneos posteriores con sesión abierta → exigen **código de sesión** (6 caracteres, sin ambiguos, rotativo, con límite de intentos) o token válido.
- Token de invitado: 256 bits aleatorios, **se guarda solo su hash**, atado a (sesión, participante), revocable, vence a las 24 h del cierre.
- Mitigación de abuso: el personal valida cada pedido (control humano), límites de pedidos pendientes, rate limiting, expiración de sesiones vacías, y opción de confirmación del personal para abrir sesión (`requireStaffToOpenSession`, apagada por defecto).
- Cuentas (staff, dueño, clientes registrados): JWT de acceso 15 min + refresh rotativo con detección de reutilización.

## Alternativas
JWT para invitados (no revocable sin lista), QR dinámico rotativo (impresión imposible), geolocalización (poco fiable, invasiva).

## Consecuencias
Hay que persistir tokens y poder revocar. Web guarda el token en `localStorage` (bajo valor, acotado a la sesión); CSP estricta y sin scripts de terceros.
