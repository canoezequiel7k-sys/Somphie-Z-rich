# Seguridad y privacidad

> Lista base de diseño, no una auditoría completa. Cada ítem tiene dueño (qué agente lo implementa) y verificación.

## 1. Modelo de amenazas (resumen STRIDE)

| Amenaza | Escenario | Mitigación | Dueño | Prueba |
|---|---|---|---|---|
| **Spoofing** | Alguien usa el QR sin estar presente | Control humano de pedidos; límites; expiraciones; opción de confirmación del personal; códigos rotativos | Backend | CL-043 |
| **Spoofing** | Robo de token de invitado | Token opaco, acotado a sesión, revocable, vence al cierre+24 h; HTTPS; CSP | Backend/Web | CL-044/046 |
| **Tampering** | Cliente manipula precio/estado | Backend recalcula todo; contrato sin campos de precio de entrada | Backend | CL-063 |
| **Repudiation** | "Yo no cancelé ese ítem" | Auditoría append-only con actor y `traceId` | Backend | RN-AUD |
| **Info disclosure** | Ver pedidos de otra mesa (IDOR) | Alcance por sesión; 404 en vez de 403; ids no secuenciales | Backend | CL-125 |
| **Info disclosure** | Enumerar emails | Respuestas genéricas | Backend | CL-120 |
| **Info disclosure** | Trazas en errores | Manejador global sin detalle interno | Backend | CL-130 |
| **DoS** | Spam de pedidos / fuerza bruta de códigos | Rate limit, límite de pendientes, bloqueo temporal | Backend | CL-042, CL-066 |
| **Elevation** | Mozo usa endpoint de dueño | Permisos por acción **y** recurso en servidor | Backend | CL-124 |
| **XSS** | Nombre/nota con script | Escape por defecto de React; sin `dangerouslySetInnerHTML`; CSP | Web | CL-128 |
| **CSRF** | Acciones forzadas desde otro sitio | API con `Authorization: Bearer` (no cookies de sesión) → sin CSRF clásico; si se usan cookies, `SameSite` + token | Backend/Web | — |
| **Secrets leak** | Claves en Git | `.env` ignorado, gitleaks en CI, secretos en el proveedor | DevOps | CI |

## 2. Autenticación y tokens

| Credencial | Formato | Duración | Almacenamiento cliente | Almacenamiento servidor |
|---|---|---|---|---|
| Invitado | Opaco 256 bits | Sesión + 24 h | Web: `localStorage` (clave por mesa) | Solo hash SHA-256 |
| Access (cuentas) | JWT firmado (claims mínimos: sub, rid, permisos resumidos, `tv`) | 15 min | Memoria (web), Keystore (Android) | — |
| Refresh | Opaco, rotativo | 30 d (personal), configurable | Android: DataStore cifrado (Keystore/Tink). Web admin: `httpOnly` cookie si mismo sitio; si no, memoria + rotación estricta (🟡 ADR-0003) | Hash + familia + `used_at` |
| Contraseña | Argon2id (preferido) o BCrypt ≥ 12 | — | Nunca | Solo hash |

Notas: la clave de firma JWT es un secreto de entorno; rotación documentada. Los permisos efectivos se **recalculan en servidor** en cada operación crítica (el JWT no es la única fuente).

## 3. Cabeceras y configuración HTTP
- HTTPS obligatorio (HSTS en producción).
- **CORS**: lista blanca explícita de orígenes (web cliente/admin); nunca `*` con credenciales.
- CSP estricta en la web (`default-src 'self'`; sin scripts de terceros; fuentes permitidas explícitamente).
- `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` mínima.
- Límite de tamaño de cuerpo y de subida de imágenes.

## 4. Rate limiting (valores iniciales 🟡)
| Endpoint | Límite |
|---|---|
| `POST /auth/login` | 5 fallos / 15 min por cuenta+IP |
| `POST …/join` | 5 fallos / 10 min por IP+mesa |
| `POST …/sessions` (abrir) | 5 / 10 min por IP |
| `POST …/orders` | 10 / min por participante |
| `POST …/payments` | 10 / min por participante |
| Resto | 120 / min por token/IP |

## 5. Privacidad y datos personales
- **Minimizar:** invitados solo dejan un nombre (≤30). Sin teléfono ni dirección.
- **No recolectar** datos de tarjeta (pagos simulados). La pantalla de pago **no** tiene campos de tarjeta.
- Emails solo para cuentas registradas y comprobantes.
- Retención: ver `modelo-datos.md` §5 (🔴 Q-07).
- Logs sin PII (emails enmascarados, sin tokens).
- Separar datos de desarrollo y de demo/producción; la demo usa datos ficticios.

## 6. Secretos y entornos
- `.env.example` documentado; `.env` en `.gitignore`; **gitleaks** en pre-commit o CI.
- Secretos de nube en el gestor del proveedor.
- Contraseñas de demo públicas y **solo** en el entorno de demo.

## 7. Seguridad de dependencias
- Dependabot/Renovate para npm, Gradle y GitHub Actions.
- `npm audit` / OWASP dependency-check en CI (informativo en MVP).

## 8. Checklist de seguridad por PR
- [ ] ¿La acción valida **permiso** y **alcance** en el servidor?
- [ ] ¿Los datos de entrada están validados (tipos, longitudes, rangos)?
- [ ] ¿Se evitó exponer ids/datos de otra sesión (IDOR)?
- [ ] ¿Se evitó registrar secretos/PII en logs?
- [ ] ¿Los errores no filtran detalles internos?
- [ ] ¿Hay prueba de acceso no autorizado?
