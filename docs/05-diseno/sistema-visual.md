# Sistema visual — Somphie Zúrich

> Dirección: **cafetería de especialidad, cálida, contemporánea y elegante.** La paleta fue aprobada para WP-UX-10; la tipografía usa las primeras alternativas ya propuestas, con archivos de fuente aún pendientes. Premium = consistencia, jerarquía y calma, no cantidad de efectos.

## 1. Principios
1. **Claridad primero:** una acción principal por pantalla.
2. **Calidez:** superficies cremosas, esquinas suaves, fotografía real y consistente.
3. **Respuesta inmediata:** todo toque tiene feedback visual en < 100 ms, sin simular que el servidor confirmó.
4. **Estados completos:** cargando, error, vacío, éxito y sin conexión se diseñan **antes** que las animaciones.
5. **Una sola fuente de tokens** para Web y Android.

## 2. Color (tokens semánticos)

| Grupo / token | Valor | Uso |
|---|---|---|
| `palette.cream` / `surface.base` | `#F6F1E8` | Fondo cálido principal |
| `palette.coffee` / `text.primary` / `surface.inverse` | `#3A2B22` | Texto y superficies de máximo contraste |
| `palette.caramel` / `accent.default` | `#945C34` | Acento y acción primaria con texto crema |
| `palette.sage` / `secondary.default` | `#61715B` | Detalles e indicadores secundarios con texto crema |
| `palette.blueGray` / `neutral.default` | `#59696E` | Acentos neutros con texto crema |
| `surface.raised` | `#FBF8F2` | Tarjetas y hojas |
| `text.secondary` | `#5B4B40` | Texto de apoyo |
| `accent.pressed` | `#7A4828` | Estado presionado |
| `border.subtle` | `#DED5C8` | Divisores |

Contraste WCAG calculado con luminancia relativa: café sobre crema **12,05:1**; crema sobre caramelo **4,86:1**, salvia **4,64:1** y gris azulado **5,09:1**. Todos superan AA para texto normal (4,5:1). El color no es el único indicador de estados.

**Modo oscuro:** fuera del MVP; los tokens semánticos lo permiten luego.

## 3. Tipografía
| Rol | Propuesta | Alternativa | Notas |
|---|---|---|---|
| Display / títulos | *Fraunces* (fallback Georgia) | Cormorant Garamond | Solo para títulos y precios destacados |
| Texto / UI | *Inter* (fallback del sistema) | DM Sans | Todo lo operativo |
| Numérico | Cifras tabulares (`font-variant-numeric: tabular-nums`) | — | Precios y totales alineados |

- Escala (web, rem): 12 / 14 / 16 / 20 / 24 / 32 / 40. Interlineado 1.4–1.6 en texto.
- Android: escala Material 3 mapeada a estos tamaños.
- Los nombres de fuente quedan como stacks en los tokens; antes de usarlos en producción, autohospedar los archivos y conservar los fallbacks.

## 4. Espaciado, forma y elevación
| Token | Valor |
|---|---|
| Espaciado base | 4 px (escala 4·8·12·16·24·32·48·64) |
| Radios | `sm 8` · `md 12` · `lg 20` · `pill 999` |
| Elevación | 3 niveles, sombras suaves cálidas (marrón con baja opacidad) |
| Objetivo táctil | ≥ 44×44 px (web) / 48 dp (Android) |
| Contenedor web cliente | Ancho máx. 480 px centrado en escritorio; panel admin: fluido hasta 1440 px |

## 5. Movimiento
| Tipo | Duración | Curva | Ejemplo |
|---|---|---|---|
| Micro-feedback | 100–150 ms | ease-out | Presión de botón |
| Transición de elemento | 200–250 ms | ease-in-out | Hoja del carrito, chips |
| Transición de pantalla | 250–300 ms | ease-in-out | Navegación |

- **Respetar `prefers-reduced-motion`** (web) y la configuración de animaciones del sistema (Android): reemplazar movimiento por fundido.
- Animar solo `transform` y `opacity` (rendimiento). Sin animaciones decorativas en bucle.
- Shimmer/skeleton para cargas > 300 ms.

## 6. Componentes base (inventario)

| Componente | Web | Android | Estados obligatorios |
|---|:-:|:-:|---|
| Botón (primario/secundario/texto/destructivo) | ✔ | ✔ | normal, presionado, foco, deshabilitado, cargando |
| Campo de texto / selector | ✔ | ✔ | normal, foco, error, deshabilitado |
| Tarjeta de producto | ✔ | — | normal, agotado, en carrito |
| Selector de cantidad | ✔ | ✔ | mín., máx., deshabilitado |
| Chip de estado (pedido/ítem/mesa) | ✔ | ✔ | un color + **texto + ícono** (nunca solo color) |
| Hoja inferior (bottom sheet) | ✔ | ✔ | — |
| Diálogo de confirmación | ✔ | ✔ | acción destructiva con motivo |
| Toast / snackbar | ✔ | ✔ | éxito, error, info |
| Skeleton | ✔ | ✔ | — |
| Estado vacío (ilustración simple + texto + acción) | ✔ | ✔ | — |
| Estado de error (mensaje + reintentar + `traceId` en detalle) | ✔ | ✔ | — |
| Banner sin conexión | ✔ | ✔ | — |
| Tabla con filtros/orden | ✔ (admin) | — | cargando, vacía, error |
| Tarjeta de pedido (cola) | — | ✔ | normal, demorado, nuevo |
| Tarjeta de mesa (tablero) | — | ✔ | los 6 `displayStatus` |
| Formulario con validación | ✔ | ✔ | errores por campo |
| Código de sesión (6 caracteres grande, copiar/compartir) | ✔ | ✔ | — |

## 7. Plantillas de estados (obligatorias en cada pantalla)
| Estado | Patrón |
|---|---|
| Cargando | Skeleton con la forma del contenido; spinner solo en acciones puntuales |
| Vacío | Texto cálido + acción sugerida |
| Error recuperable | Mensaje humano + botón "Reintentar" |
| Error de permiso | Mensaje claro, sin botón de reintento |
| Sin conexión | Banner persistente; contenido en caché si existe; acciones de escritura deshabilitadas |
| Éxito | Confirmación breve (check + texto), sin bloquear |
| Procesando acción | Botón en "cargando" y bloqueado (evita doble envío) |

## 8. Accesibilidad (checklist de diseño)
- [ ] Contraste AA en todos los pares.
- [ ] Estados comunicados con texto/ícono además de color.
- [ ] Foco visible y orden de tabulación lógico (web).
- [ ] Etiquetas accesibles (`aria-label`, `contentDescription`) en íconos y botones.
- [ ] Texto escalable hasta 200 % sin romper el diseño.
- [ ] Imágenes con texto alternativo (campo obligatorio en catálogo).
- [ ] Formularios con errores asociados al campo.
- [ ] Respeta movimiento reducido.

## 9. Tono de voz (es-AR)
Cercano y claro, trato de **vos**. Sin jerga técnica en errores.
| Situación | Ejemplo |
|---|---|
| Pedido enviado | "¡Listo! Tu pedido #042 está esperando confirmación." |
| Rechazo | "No pudimos aceptar tu pedido: se agotó la medialuna. Podés corregirlo y enviarlo de nuevo." |
| Precio cambió | "Actualizamos algunos precios. Revisá el total antes de confirmar." |
| Sin conexión | "Sin conexión. Intentamos de nuevo en unos segundos." |
| Código erróneo | "Ese código no coincide. Pedíselo a quien abrió la mesa." |

## 10. Adaptación por plataforma
| Plataforma | Prioriza | Notas |
|---|---|---|
| Web cliente | Navegación móvil, selección rápida, claridad de cuenta | Pulgar: acciones principales abajo; barra fija con total del carrito |
| Android personal | Densidad, lectura rápida, acciones operativas | Alto contraste, botones grandes, vibración/sonido opcional en pedido nuevo |
| Panel admin | Tablas, filtros, formularios, análisis | Teclado, atajos básicos, tablas accesibles |

## 11. Entregables del agente UX (ver roadmap)
- `design-tokens.json` (única fuente) → CSS variables (web) y `Color.kt/Type.kt/Shape.kt` (Android).
- Wireframes de baja y alta fidelidad de todas las pantallas del `mapa-pantallas.md`.
- Biblioteca de componentes con estados.
- Logo/isotipo, íconos, ilustraciones de estados vacíos, fotografías de ejemplo.

## 12. Marca (D-134)
`design/img/ic_logo.svg` es el activo entregado por el dueño: tiene un `viewBox` cuadrado y se conserva intacto, sin rediseñarlo ni reemplazarlo. No hay un isotipo independiente para favicon/Android; la derivación queda pendiente de Q-21 o de autorización expresa.
