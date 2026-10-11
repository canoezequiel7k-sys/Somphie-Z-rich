# Agente Android (app del personal)

## Misión
Entregar la **app operativa del personal**: cola de pedidos, preparación por estación, tablero de mesas, cobro en caja y cierre, según permisos. Rápida, legible y robusta ante desconexiones. **Muestra y envía acciones; no decide.**

## Lee (en orden)
1. `AGENTS.md` · 2. `01-producto/actores-y-roles.md` · 3. `02-dominio/estados-y-transiciones.md`, `casos-de-uso.md` (CU-20…36), `catalogo-errores.md`, `reglas-negocio.md` (RN-ORD, RN-PRE, RN-BIL) · 4. `04-api/*` · 5. `05-diseno/*` (SC-S-*) · 6. `03-arquitectura/tiempo-real.md`.

## Posee
`android/`: app Compose, tema/tokens, red, almacenamiento seguro, pruebas Android.

## No hace
- Cambiar un estado **localmente** como si fuera definitivo (siempre confirma el servidor).
- Decidir permisos: usa `GET /me` y el servidor valida.
- Duplicar acciones al reconectar.

## Stack (ADR-0001)
Kotlin · Jetpack Compose · Material 3 (tema propio) · Hilt · Navigation Compose · Coroutines/Flow · Retrofit + OkHttp + kotlinx.serialization · OkHttp-SSE (F6) · DataStore + Keystore/Tink (tokens) · JUnit, MockK, Turbine, Compose UI Test, MockWebServer.

## Estructura (paquetes por funcionalidad; sin multi-módulo prematuro)
```
android/app/src/main/kotlin/com/somphie/staff/
├── core/        network (auth interceptor, refresh), storage, design (tema), i18n (strings), util (money)
├── feature/
│   ├── auth/        login, cambio de contraseña
│   ├── inbox/       entrantes, detalle, aceptar/rechazar/excluir
│   ├── stations/    colas, acciones de ítem
│   ├── tables/      tablero, detalle de mesa/sesión, cierre
│   ├── billing/     pago en caja, reembolso (F7)
│   └── availability/ disponibilidad de productos
└── app/         MainActivity, NavHost, DI
```
Cada feature: `ui` (Composable + ViewModel + UiState) · `data` (API, DTO, repositorio, mappers) · `domain` (modelos y casos de uso **solo si hay regla real**).

## Reglas de oro
1. **Flujo unidireccional:** `UiState` inmutable (`Loading | Content | Empty | Error`) + eventos de una sola vez por canal.
2. Pestañas y acciones salen de **`permissions` y `stations` de `/me`**, no del nombre del rol.
3. Cada acción envía **`Idempotency-Key`**; al reintentar tras fallo de red, **misma clave**.
4. Ante `409` (`ORDER_ALREADY_DECIDED`, `ITEM_INVALID_TRANSITION`…): **refrescar** el recurso y explicar con los datos del `extra` (quién/qué).
5. Tras `TOKEN_EXPIRED`: refrescar **una vez** (mutex para evitar refrescos simultáneos); si falla → login.
6. Tokens en **DataStore cifrado** (Keystore). Nada en logs.
7. Compose: claves estables en `LazyColumn`, `remember`/`derivedStateOf` con criterio, evitar recomposiciones innecesarias, trabajo pesado fuera del hilo principal.
8. Estados de carga/error/vacío/offline por pantalla; **no bloquear toda la UI** por un error parcial.
9. Textos en `strings.xml` (es-AR); sin literales en Composables.
10. Tolerar enums desconocidos.
11. Accesibilidad: `contentDescription`, objetivos ≥ 48 dp, contraste AA, estados por texto+ícono.
12. Sonido/vibración opcional en pedido nuevo (F6), configurable.

## Trabajar sin backend
- **Prism** (`http://10.0.2.2:4010`) desde `openapi.yaml`, o repositorios *fake* con los ejemplos del OpenAPI; pruebas de red con **MockWebServer** y fixtures del contrato.
- Al integrar: cambiar `baseUrl` por build type; correr el flujo instrumentado.

## Entregables por fase
Ver `roadmap-fases.md` (`WP-AND-*`).

## Necesita de otros
| De | Qué | Cuándo |
|---|---|---|
| Arquitecto | OpenAPI + ejemplos + errores + estados + eventos | F1 |
| UX | Tokens, pantallas SC-S, componentes, íconos, sonidos | Antes de cada pantalla |
| Backend | Auth staff, colas, transiciones, tablero, SSE | F4–F7 |
| DevOps | CI Android, firma, releases | F2/F10 |
| QA | Casos de pruebas instrumentadas | Continuo |

## Provee a otros
| A | Qué |
|---|---|
| Backend/Arquitecto | Feedback de contrato (campos que faltan para la UI: `productCurrentlyAvailable`, `delayed`, etc.) |
| QA | Etiquetas de test estables (`testTag`) en flujos críticos |

## DoD (además de `AGENTS.md` §5)
- [ ] Probado en emulador y, idealmente, un dispositivo real.
- [ ] Pruebas de ViewModel (estados) y de UI de la pantalla.
- [ ] Revisado con Layout Inspector / compose compiler metrics si hay listas largas.
- [ ] Sin `localhost`: URL por *build type*.
- [ ] Manejo de reconexión probado (modo avión).

## Cambios v0.2 (leer)
- La app sigue **solo en es-AR**.
- Mostrar **opciones** (`optionsSummary`) en colas y detalle de pedido: es lo que más necesita barra.
- **Ajustes:** pantalla para aplicar/anular con motivo; mostrar `ADJUSTMENT_LIMIT_EXCEEDED` como "requiere al Dueño".
- Disponibilidad: productos **y opciones**. Llamados al mozo: insignia en tablero y aviso (F9).
- Pago en caja: admite propina.
