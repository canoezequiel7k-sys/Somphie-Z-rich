# Agente Web (cliente por QR + panel admin)

## Misión
Construir la **web móvil del cliente** y el **panel de administración** en un único proyecto React + TypeScript, con experiencia premium, accesible y rápida. **Muestra; no decide.**

## Lee (en orden)
1. `AGENTS.md` · 2. `01-producto/vision.md`, `actores-y-roles.md` · 3. `02-dominio/casos-de-uso.md` (CU-01…14, 40…45), `estados-y-transiciones.md`, `catalogo-errores.md`, `reglas-negocio.md` (RN-ORD, RN-BIL, RN-ACC) · 4. `04-api/*` · 5. `05-diseno/*` · 6. `03-arquitectura/tiempo-real.md`.

## Posee
`web/`: rutas `/` (cliente) y `/admin` (panel, **carga diferida**), cliente API tipado, tokens CSS, textos es-AR, pruebas web y E2E (Playwright, junto a QA).

## No hace
- Calcular precio final, estado, permiso o saldo como verdad.
- Guardar o pedir datos de tarjeta.
- Inventar nombres de estados/errores.
- Importar código entre `features/client` y `features/admin` (solo vía `shared`).

## Stack (ADR-0001)
Vite · React · TypeScript estricto · React Router · **TanStack Query** (estado de servidor) · **Zustand** (carrito) · react-hook-form + zod · Radix primitives · Motion · CSS con tokens (variables) · openapi-typescript · Vitest + RTL + **MSW** · Playwright · ESLint (+ boundaries) + Prettier.

## Estructura
```
web/src/
├── app/            router, providers, layouts, error boundaries
├── shared/         api (cliente generado), ui (kit), hooks, i18n/es-AR.ts, utils (money, dates)
├── features/
│   ├── client/     table-access, menu, cart, orders, bill, payments, participants
│   └── admin/      auth, catalog, tables, users, reports, audit, settings
└── tokens/         design-tokens → css variables
```

## Reglas de oro
1. **Tipos del contrato generados** desde `openapi.yaml`; no se escriben a mano.
2. El **total del carrito es una estimación** (`estimatedTotalMinor`); el servidor es la verdad. Enviar `acknowledgedTotalMinor` y manejar `PRICE_CHANGED`.
3. **`Idempotency-Key` una vez por intento lógico** (guardada en el estado de la mutación; se reutiliza al reintentar; se renueva si cambian los datos).
4. Botones de acción: **deshabilitados mientras procesan**; sin "optimismo" para estados críticos (pedido aceptado, pago).
5. Mapear **`code` → mensaje es-AR** en un único lugar (`shared/i18n/errors.ts`); nunca mostrar `detail` técnico.
6. Toda pantalla maneja **cargando, vacío, error, sin conexión, éxito**.
7. Tolerar **enums desconocidos** (estado "desconocido" sin romper).
8. Sin `dangerouslySetInnerHTML`; todo texto de usuario se escapa.
9. Token de invitado: clave por mesa en `localStorage`; limpiar ante `TOKEN_REVOKED`/`SESSION_CLOSED` (tras lectura 24 h).
10. Dinero con `Intl.NumberFormat('es-AR', {style:'currency', currency:'ARS'})` sobre centavos enteros.
11. Rendimiento: panel `/admin` con `React.lazy`; imágenes `loading="lazy"` y tamaños fijos (evitar CLS); JS inicial < 200 KB gzip.
12. Accesibilidad: foco visible, `aria-*`, contraste, `prefers-reduced-motion`.

## Trabajar sin backend (mientras no esté listo)
1. Levantar **Prism** (`:4010`) desde `openapi.yaml` y apuntar `VITE_API_BASE_URL` allí; o
2. Usar **MSW** con handlers basados en los **ejemplos** del OpenAPI.
Al integrar con el backend real: cambiar la URL y correr los E2E; **no** reescribir pantallas.

## Entregables por fase
Ver `roadmap-fases.md` (`WP-WEB-*`).

## Necesita de otros
| De | Qué | Cuándo |
|---|---|---|
| Arquitecto | `openapi.yaml` + ejemplos + catálogo de errores | F1 |
| UX | Tokens, wireframes, componentes, assets, textos | Antes de cada pantalla |
| Backend | API real, semillas, SSE | F3, F4, F6, F7, F8 |
| DevOps | Compose, CI, hosting estático | F2 |
| QA | Escenarios E2E | Continuo |

## Provee a otros
| A | Qué |
|---|---|
| QA | `data-testid`/roles accesibles estables en flujos críticos |
| Backend | Feedback de fricción del contrato (vía consulta) |
| UX | Realidad de implementación (limitaciones, rendimiento) |

## DoD (además de `AGENTS.md` §5)
- [ ] Probado en móvil real o emulado (≈ 360–430 px) y escritorio.
- [ ] Pruebas de lógica (carrito, formatos), de componentes y E2E del flujo tocado.
- [ ] Lighthouse móvil dentro de presupuesto.
- [ ] axe sin violaciones críticas.
- [ ] Textos solo en `i18n/es-AR`.

## Cambios v0.2 (leer)
- **i18n desde el esqueleto** (es-AR + en): ninguna cadena fuera de los archivos de idioma; errores por `code`. Guardar el carrito por `productId`.
- **Opciones:** hoja de selección con reglas de grupo; líneas distintas por combinación; el total sigue siendo estimación.
- **Pago:** campo de propina libre (ayudas visuales opcionales, Q-18); la propina no cambia el saldo mostrado.
- **Cuenta:** mostrar ajustes con etiqueta genérica (sin motivo interno) y propinas aparte.
- **Imágenes:** URL = `MEDIA_BASE_URL + imagePath`; placeholder si falla.
- **Despliegue:** Vercel. Backend en otro dominio → CORS con orígenes exactos; no usar cookies de sesión.
- **Llamar al mozo:** botón con motivos (incluye "Pedir la cuenta").
