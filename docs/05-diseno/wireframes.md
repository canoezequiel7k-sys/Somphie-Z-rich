# Wireframes de baja fidelidad y matriz de cobertura de estados (WP-UX-11)

Este documento define los wireframes de baja fidelidad (esquemas estructurales) y la matriz de cobertura de estados obligatorios para todas las pantallas y superficies de Somphie Zürich, conforme a `docs/05-diseno/mapa-pantallas.md` y las reglas de UI (`AGENTS.md` §2.10).

---

## 1. Matriz de Cobertura de Estados Obligatorios

Toda pantalla interactiva del sistema (Web cliente y App Android) debe tener diseño y manejo explícito para los siguientes 5 estados:

| Estado | Definición | Comportamiento esperado en UI |
|---|---|---|
| **1. Cargando (Loading)** | Datos en tránsito o inicialización | Skeleton screens o indicador de progreso sutil (sin bloquear toda la pantalla a menos que sea crítico). |
| **2. Éxito (Success)** | Datos cargados o acción completada | Visualización de contenido, toasts de confirmación breves, redirección limpia. |
| **3. Vacío (Empty)** | Sin registros (ej. sin pedidos, carrito vacío) | Ilustración/ícono sutil, texto explicativo ("Aún no hay pedidos en esta mesa") y llamada a la acción (CTA) clara. |
| **4. Error (Error)** | Fallo de red, validación o servidor | Mensaje descriptivo amigable, código de error si aplica, botón de reintento ("Reintentar") y opción de soporte si persiste. |
| **5. Sin Conexión (Offline)** | Pérdida de conectividad | Banner superior no intrusivo ("Sin conexión a internet. Mostrando datos cacheados"), desactivación temporal de acciones de escritura. |

---

## 2. Wireframes de Baja Fidelidad — Vista Cliente Web (`SC-C-*`)

### SC-C-01: Entrada QR / Resolución de Mesa
```text
+------------------------------------+
|  [Logo Somphie Zürich]             |
|                                    |
|       [Escaneando QR / Mesa #3]    |
|                                    |
|   Estado: Verificando mesa...      |
|   [==================>     ]       |
|                                    |
|   [Cargando...]                    |
+------------------------------------+
```

### SC-C-04: Menú (Categorías + Productos)
```text
+------------------------------------+
|  Mesa #3 | [🔍 Buscar] | [🛒 (2)]   |
+------------------------------------+
| [Cafés] [Bebidas] [Panadería] [Sal]|
+------------------------------------+
| [Img] Café Cortado                 |
|       Equilibrado con leche...     |
|       $ 2.500 ARS        [ + ]     |
+------------------------------------+
| [Img] Medialuna de Manteca         |
|       Recién horneada              |
|       $ 1.200 ARS        [ + ]     |
+------------------------------------+
| [ Estado: Conectado | Barra Nav ]  |
+------------------------------------+
```

### SC-C-07: Carrito y Revisión
```text
+------------------------------------+
| < Volver al Menú      Mi Carrito   |
+------------------------------------+
| Mesa #3 - Resumen de Pedido        |
|                                    |
| 1x Café Cortado         $ 2.500    |
| 2x Medialunas           $ 2.400    |
| ---------------------------------- |
| Subtotal                $ 4.900    |
| Propina sugerida (10%)    $ 490    |
| ---------------------------------- |
| TOTAL ESTIMADO          $ 5.390    |
|                                    |
| [ Nota para cocina (opcional) ]    |
|                                    |
| [ ENVIAR PEDIDO A COCINA ]         |
+------------------------------------+
```

### SC-C-08: Estado del Pedido
```text
+------------------------------------+
| Estado del Pedido                  |
+------------------------------------+
| Pedido #104                        |
| [⏳ Pendiente de revisión]         |
| Tiempo estimado: ~10 min           |
|                                    |
| Ítems:                             |
| • 1x Café Cortado (En preparación) |
| • 2x Medialunas   (Listo)          |
|                                    |
| [ Actualizar estado ]              |
+------------------------------------+
```

---

## 3. Wireframes de Baja Fidelidad — App Android del Personal (`SC-S-*`)

### SC-S-03: Bandeja de Entrantes (Mozo/Cocina)
```text
+------------------------------------+
| ☰ Entrantes             [🔔 3]   |
+------------------------------------+
| [Filtros: Todos | Cocina | Barra]  |
+------------------------------------+
| Mesa #3 • Hace 2 min               |
| 3 ítems • Pendiente de revisión    |
| [ ACEPTAR ]             [ RECHAZAR]|
+------------------------------------+
| Mesa #7 • Hace 5 min               |
| 1 ítem • Pendiente de revisión     |
| [ ACEPTAR ]             [ RECHAZAR]|
+------------------------------------+
```

### SC-S-07: Tablero de Mesas
```text
+------------------------------------+
| ☰ Tablero de Mesas      [ + Sesión]|
+------------------------------------+
| [Mesa 1]      [Mesa 2]      [Mesa 3]|
| Libre         Ocupada       Atendida|
| Saldo: $0     Saldo: $5.4k  Pend. 1 |
+------------------------------------+
| [Mesa 4]      [Mesa 5]      [Mesa 6]|
| Cerrando      Libre         Reserv. |
| Saldo: $0     Saldo: $0     Saldo: $0|
+------------------------------------+
```
