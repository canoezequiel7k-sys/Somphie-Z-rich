# Registro de decisiones

Leyenda: ✅ Confirmada (definida por ti en el plan maestro) · 🟢 Aceptada en chat · 🟡 Propuesta (**requiere tu validación**) · 🔴 Pendiente.

## 1. Decisiones confirmadas (plan maestro)

| ID | Decisión | Estado |
|---|---|---|
| D-001 | Proyecto independiente de portfolio (no atado al TFI) | ✅ |
| D-002 | Planificar el alcance completo desde el inicio; implementar por fases pequeñas | ✅ |
| D-003 | Una sola cafetería, **diseñada para ampliarse** (todas las tablas llevan `restaurant_id`) | ✅ |
| D-004 | Entrega demostrable (demo pública + README + video/capturas) | ✅ |
| D-005 | Un empleado autorizado decide aceptar o rechazar cada pedido | ✅ |
| D-006 | Pago simulado; nunca dinero real ni datos de tarjeta | ✅ |
| D-007 | Cuenta compartida o dividida entre participantes (pagos parciales por monto) | ✅ |
| D-008 | Se puede pedir como invitado; una cuenta registrada habilita extras | ✅ |
| D-009 | Autenticación por email | ✅ |
| D-010 | Extras del TFI: solo los que aporten valor al portfolio | ✅ |
| D-011 | Tiempo real, cuando el flujo base REST funcione | ✅ |
| D-012 | Email y PDF incluidos en el alcance | ✅ |
| D-013 | Diseño de nivel premium | ✅ |
| D-014 | IA como pareja de programación; se prioriza aprender el porqué | ✅ |
| D-015 | Stack: Kotlin + Compose, React + TS, Spring Boot + Kotlin, PostgreSQL | ✅ |
| D-016 | Despliegue en nube temprano con presupuesto cero | ✅ |
| D-017 | Documentación desde el primer día | ✅ |
| D-018 | Entorno Windows + Git | ✅ |
| D-019 | Clean Architecture sin sobreingeniería | ✅ |
| D-020 | Nombre del producto: **Somphie Zúrich** | 🟢 |

## 2. Decisiones aceptadas en chat

| ID | Decisión | Estado |
|---|---|---|
| D-021 | Autorización por **permisos**; los roles son paquetes de permisos. MVP: Cliente (invitado/registrado), Personal (Mozo, Barra, Cocina), Dueño. Maître y Supervisor se agregan después sin migrar el modelo | 🟢 |
| D-022 | El QR contiene solo un identificador opaco de mesa. El primer escaneo abre la sesión y entrega un token de invitado acotado; quien se suma después usa un código corto | 🟢 |
| D-023 | Cuenta compartida, pagos parciales por monto, sin sobrepago. Cierra el personal con saldo 0 y sin pedidos en curso. El dueño puede forzar el cierre (auditado) | 🟢 |
| D-024 | Rechazo con motivo opcional, conservado en historial; el cliente corrige y reenvía | 🟢 |
| D-025 | MVP: servicio en mesa. Retiro por mostrador en Fase 9. Delivery fuera de alcance | 🟢 |
| D-026 | Primero REST (+polling); luego se evalúa **SSE antes que STOMP** (ver ADR-0006) | 🟢 |
| D-027 | Un único proyecto React; el panel vive en `/admin`, con carga diferida | 🟢 |
| D-028 | Fase 0 (especificación) completa antes de escribir código | 🟢 |

## 3. Propuestas de la v0.1 — resultado de tu validación

Todas quedaron 🟢 **aceptadas** (D-101 a D-128), salvo **D-121**, que no traía marca: queda 🟡 a la espera de tu confirmación (recomiendo aceptarla).

| ID | Decisión | Estado |
|---|---|---|
| D-101 | Dinero en enteros de centavos, moneda única ARS | 🟢 |
| D-102 | IDs UUID + código visible `#NNN` por día de negocio | 🟢 |
| D-103 | Código/API/DB/commits en inglés; docs y UI en es-AR (la UI cliente suma `en`, ver D-140) | 🟢 |
| D-104 | Contract-first con `openapi.yaml` a mano | 🟢 |
| D-105 | Token de invitado opaco; cuentas con JWT corto + refresh rotativo | 🟢 |
| D-106 | Límites operativos configurables en `restaurant_settings` | 🟢 |
| D-107 | `PRICE_CHANGED` con total reconocido | 🟢 |
| D-108 | Pedido pendiente expira a los 15 min | 🟢 |
| D-109 | Sesión vacía expira a los 30 min | 🟢 |
| D-110 | Aceptar con exclusiones | 🟢 |
| D-111 | Cancelar ítems con motivo + reembolso simulado | 🟢 |
| D-112 | Pagos en efectivo registrados por el personal | 🟢 |
| D-113 | Pedido a nombre del cliente (auto-aceptado) | 🟢 |
| D-114 | Abrir sesión desde el personal | 🟢 |
| D-115 | Aceptan/rechazan: Mozo, Barra, Dueño | 🟢 |
| D-116 | Disponibilidad: Mozo, Barra, Cocina, Dueño (ahora también opciones) | 🟢 |
| D-117 | Token en solo lectura 24 h tras el cierre | 🟢 |
| D-118 | SSE + `event_log` + `Last-Event-ID` | 🟢 |
| D-119 | Horario de atención y `RESTAURANT_CLOSED` | 🟢 |
| D-120 | Monorepo | 🟢 |
| D-121 | Tests de contrato (`swagger-request-validator`) + mocks Prism | 🟡 **confirmar** |
| D-122 | APK por GitHub Releases | 🟢 |
| D-123 | Notas por ítem (≤140). **Opciones/modificadores pasan de "reservadas" a incluidas en MVP** (ver D-129) | 🟢 |
| D-124 | Participantes `HOST`/`MEMBER` | 🟢 |
| D-125 | Modo demo: centavos `.13` simulan rechazo | 🟢 |
| D-126 | Divisor de cuenta solo en UI | 🟢 |
| D-127 | Módulo `audit` | 🟢 |
| D-128 | `orders` dueño del agregado Pedido+Ítems | 🟢 |

## 3.1 Decisiones nuevas derivadas de tus respuestas (v0.2)

| ID | Decisión | Origen | Estado |
|---|---|---|---|
| D-129 | **Opciones/modificadores en el MVP** (tamaño, leche, extras con precio). Grupos reutilizables asignables a varios productos; snapshot histórico en el ítem; disponibilidad alternable. Entra en **Fase 3** | Q-02 | 🟢 (mecánica 🟡) |
| D-130 | **Propina libre**: monto voluntario que el participante adjunta a su pago (o paga solo propina). No reduce el saldo, no es cargo, se reporta aparte. Tope de cordura configurable para evitar errores de tipeo | Q-01 | 🟢 (tope 🟡) |
| D-131 | **Descuentos, cortesías y ajustes manuales** en el alcance (los tres tipos). Motivo obligatorio, auditoría, límite para el Mozo y sin límite para el Dueño; importe congelado al aplicarlo | Q-03 | 🟢 (mecánica 🟡) |
| D-132 | **Imágenes en carpeta**: puerto `ImageStorage` con adaptador de carpeta. En la demo, imágenes versionadas en el repo y servidas como estáticos | Q-04 | 🟢 |
| D-133 | **Web (cliente + admin) en Vercel** mientras sea gratis. El backend y PostgreSQL **no** van en Vercel (ver Q-15) | Q-05 | 🟢 / 🔴 backend |
| D-134 | Marca en `design/img/` (`ic_icon.png`, etc.) | Q-06 | 🟢 |
| D-135 | Retención de datos: se adopta la propuesta de `modelo-datos.md` §5; nombres de invitados se anonimizan a los 12 meses (🟡 plazo) | Q-07 | 🟢 |
| D-136 | Comprobante: leyenda "Comprobante no fiscal — no válido como factura" + datos ficticios del local | Q-08 | 🟢 |
| D-137 | Alérgenos: lista de 14 (`GLUTEN, CRUSTACEANS, EGG, FISH, PEANUTS, SOY, MILK, TREE_NUTS, CELERY, MUSTARD, SESAME, SULPHITES, LUPIN, MOLLUSCS`); **verificar contra el Código Alimentario Argentino** antes de publicar | Q-09 | 🟢 / 🟡 verificar |
| D-138 | **Llamar al mozo** incluido (con motivo "pedir la cuenta"). Puede adelantarse a justo después de la Fase 6 | Q-10 | 🟢 |
| D-139 | Sin impresión de comandas por ahora; disponibilidad **manual** (sin stock real) | Q-11/Q-12 | 🟢 |
| D-140 | **i18n en la web cliente**: es-AR + en desde el diseño; alemán después. Panel admin y app Android solo es-AR. Catálogo con traducciones por idioma | Q-13 | 🟢 (idiomas 🟡) |
| D-141 | **Multi-sucursal simulada**: `restaurant_id` en todo y pruebas de aislamiento con dos restaurantes; sin UI multi-local | Q-14 | 🟢 |

## 4. Preguntas

### 4.1 Resueltas
| ID | Resolución |
|---|---|
| Q-01 | Propina libre → D-130 |
| Q-02 | Sí, en MVP → D-129 |
| Q-03 | Sí (descuentos, cortesías y ajustes) → D-131 |
| Q-04 | En una carpeta → D-132 |
| Q-05 | Vercel para la web → D-133 |
| Q-06 | `design/img/` → D-134 |
| Q-07 | Sí → D-135 |
| Q-08, Q-09 | Aceptadas → D-136, D-137 |
| Q-10 | Sí → D-138 |
| Q-11, Q-12 | Posterior / manual → D-139 |
| Q-13 | Sí → D-140 |
| Q-14 | Simulada → D-141 |

### 4.2 Nuevas pendientes 🔴
| ID | Pregunta | Impacto | Cuándo |
|---|---|---|---|
| Q-15 | **Dónde corre el backend y PostgreSQL** (Vercel no es adecuado para una app Spring Boot de larga duración ni para SSE). Verificar candidatos vigentes con plan gratuito | Despliegue, CORS, SSE | Fase 2 |
| Q-16 | Idiomas de la web cliente: ¿`en` ya en el MVP y `de` después? ¿Hace falta **traducir el menú** o solo la interfaz? | Catálogo, admin | Antes de Fase 3 |
| Q-17 | Descuentos: ¿límite del Mozo? Propuesta: **10 %** del total de cargos | Permisos, ajustes | Antes de Fase 7 |
| Q-18 | Propina: ¿solo campo libre o también montos rápidos (sugeridos)? ¿Se atribuye a un empleado? Propuesta: campo libre + chips de ayuda; sin atribución | Cuenta, reportes | Antes de Fase 7 |
| Q-19 | Confirmar **D-121** | Contrato | Antes de Fase 1 |
| Q-20 | Imágenes: ¿se permite subir desde el admin en la demo (no persiste en hosting efímero) o solo imágenes del repo? | Admin, demo | Antes de Fase 8 |
| Q-21 | Marca: ¿hay versión vectorial (SVG) además de `ic_icon.png`? ¿Logo horizontal? | Diseño | Fase 1 |

## 5. Consultas abiertas (agentes → arquitecto)

> Un agente bloqueado agrega una fila. El arquitecto responde y, si cambia algo, actualiza docs y `CHANGELOG`.

| # | Fecha | Agente | Consulta | Estado | Respuesta |
|---|---|---|---|---|---|
| — | — | — | (vacío) | — | — |

## 6. Protocolo de cambios

1. **Cambio de regla de negocio / estado / error / endpoint** → se modifica primero el documento fuente. Se anota en `04-api/CHANGELOG.md` si afecta al contrato.
2. **Cambio compatible** (campo opcional nuevo, error nuevo): aviso a Web y Android; no rompe.
3. **Cambio incompatible** (renombrar, quitar, cambiar semántica): requiere ADR o nota en decisiones, versión nueva del contrato y PR coordinado (backend + clientes).
4. Los agentes **no** cambian por su cuenta nombres de estados, errores ni respuestas.
