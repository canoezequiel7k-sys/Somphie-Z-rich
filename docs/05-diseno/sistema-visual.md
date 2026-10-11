# Sistema visual — Somphie Zúrich

> Dirección: **cafetería de especialidad, cálida, contemporánea y elegante.** Propuesta de partida 🟡; la paleta y la tipografía finales se cierran en WP-UX-10 (Q-06). Premium = consistencia, jerarquía y calma, no cantidad de efectos.

## 1. Principios
1. **Claridad primero:** una acción principal por pantalla.
2. **Calidez:** superficies cremosas, esquinas suaves, fotografía real y consistente.
3. **Respuesta inmediata:** todo toque tiene feedback visual en < 100 ms, sin simular que el servidor confirmó.
4. **Estados completos:** cargando, error, vacío, éxito y sin conexión se diseñan **antes** que las animaciones.
5. **Una sola fuente de tokens** para Web y Android.

## 2. Color (tokens semánticos)

| Token | Valor propuesto | Uso |
|---|---|---|
| `surface.base` | `#FAF6EF` (marfil) | Fondo principal |
| `surface.raised` | `#F3EBDD` (crema) | Tarjetas, hojas |
| `surface.inverse` | `#3B2A20` (espresso) | Barras, énfasis profundo |
| `text.primary` | `#3B2A20` | Texto principal (contraste ≈ 12:1 sobre marfil) |
| `text.secondary` | `#6B5646` | Texto de apoyo (verificar ≥ 4.5:1) |
| `accent.default` | `#C28A4B` (caramelo) | Acento, botón primario (texto **espresso** encima, ≈ 4.6:1) |
| `accent.pressed` | `#A87438` | Estado presionado |
| `secondary.default` | `#8A9A7B` (salvia) | Chips, fondos suaves (**no** como texto sobre marfil) |
| `state.success` | `#4F7A57` | Éxito, "Listo" |
| `state.warning` | `#B7791F` | Demora, atención |
| `state.error` | `#B3402F` | Errores |
| `state.info` | `#4A6B8A` | Información |
| `border.subtle` | `#E4D8C5` | Divisores |

> **Regla de contraste:** el caramelo y la salvia **no** se usan como color de texto sobre marfil. Todo par texto/fondo debe pasar **WCAG AA** (4.5:1 texto normal, 3:1 texto grande/íconos). WP-UX-10 valida cada par con una herramienta de contraste y deja la tabla de resultados.

**Modo oscuro:** fuera del MVP; los tokens semánticos lo permiten luego.

## 3. Tipografía (propuesta)
| Rol | Propuesta | Alternativa | Notas |
|---|---|---|---|
| Display / títulos | Serif elegante (p. ej. *Fraunces* o *Cormorant Garamond*) | Playfair Display | Solo para títulos y precios destacados |
| Texto / UI | Sans humanista (p. ej. *Inter* o *DM Sans*) | Source Sans 3 | Todo lo operativo |
| Numérico | Cifras tabulares (`font-variant-numeric: tabular-nums`) | — | Precios y totales alineados |

- Escala (web, rem): 12 / 14 / 16 / 20 / 24 / 32 / 40. Interlineado 1.4–1.6 en texto.
- Android: escala Material 3 mapeada a estos tamaños.
- Fuentes **autohospedadas** (rendimiento y CSP). Siempre con *fallback stack*.

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
Archivos en `design/img/`: `ic_icon.png` (isotipo, cuadrado, fondo transparente, ≥ 1024×1024). Pendiente Q-21: versión SVG y logo horizontal. A partir de `ic_icon.png` UX deriva favicon, íconos de Android y la cabecera del comprobante.
