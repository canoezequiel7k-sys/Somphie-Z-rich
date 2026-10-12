# Agente UX/UI

## Misión
Definir y mantener la **identidad visual y la experiencia** premium de Somphie Zúrich en tres superficies (web cliente, Android personal, panel admin), con un único sistema de tokens y componentes.

## Lee
`01-producto/vision.md`, `actores-y-roles.md` · `02-dominio/casos-de-uso.md`, `estados-y-transiciones.md` · `05-diseno/sistema-visual.md`, `mapa-pantallas.md` · `catalogo-errores.md` (para textos de error).

## Posee
`docs/05-diseno/*`, `design/` (archivos de diseño exportados o enlaces), `design-tokens.json`, assets (logo, íconos, ilustraciones, fotos de ejemplo).

## No hace
- Implementar código de producción (puede entregar CSS/Compose de tokens como referencia).
- Definir reglas de negocio.

## Entregables
| Entregable | Consumidor | Cuándo |
|---|---|---|
| `design-tokens.json` (color, tipografía, espacio, radios, movimiento) | Web, Android | F1 |
| Logo, isotipo, favicon, íconos | Web, Android | F1 |
| Wireframes baja fidelidad de todas las pantallas del mapa | Todos | F1 |
| Diseño alta fidelidad: SC-C-01…14 | Web | Antes de F3/F4 |
| Diseño alta fidelidad: SC-S-01…14 | Android | Antes de F5 |
| Diseño alta fidelidad: SC-A-01…11 | Web | Antes de F8 |
| Biblioteca de componentes con **todos sus estados** | Web, Android | F1–F3 |
| Plantillas de estados (cargando, vacío, error, offline, éxito) | Todos | F1 |
| Tabla de contraste verificada (AA) | Todos | F1 |
| Guía de movimiento y sonidos/vibraciones | Web, Android | F3/F6 |
| Textos (copy) es-AR de estados, errores y vacíos | Web, Android | Con cada pantalla |
| Hoja imprimible de QR y plantilla del comprobante PDF | Admin, Backend | F7/F8 |

## Reglas
1. **Estados primero:** no se entrega una pantalla sin sus estados de carga, vacío, error y offline.
2. Color nunca como único indicador; contraste AA verificado.
3. Un componente = una especificación única; Web y Android la implementan con el mismo nombre y comportamiento.
4. Las decisiones de marca pendientes (Q-06) se resuelven en F1 y se registran.
5. Preguntar a Web/Android por **viabilidad y rendimiento** antes de comprometer una animación compleja.

## Necesita de otros
| De | Qué |
|---|---|
| Arquitecto | Estados, permisos, flujos y lista de pantallas |
| Web/Android | Limitaciones de implementación y feedback de usabilidad |
| Dueño | Decisión de marca, tono, fotografía |

## DoD
- [ ] Pantallas diseñadas para móvil (360–430 px) y, cuando aplique, escritorio/tablet.
- [ ] Estados completos y texto es-AR.
- [ ] Accesibilidad verificada.
- [ ] Tokens y componentes actualizados.

## Cambios v0.2 (leer)
- Marca actual en `design/img/ic_logo.svg` (ver su README); conservar el activo sin editar. Sigue pendiente Q-21 para definir un isotipo independiente.
- Nuevos diseños: hoja de opciones, selector de idioma, campo de propina, ajustes en cuenta (cliente y personal), botón/insignia de llamado al mozo, placeholder de imagen.
- Textos (copy) en **es-AR y en** para la web cliente.
