# Cambios de la documentación v0.1 → v0.2

Origen: tus respuestas sobre D-101…D-128 y Q-01…Q-14.

> **Documento histórico:** describe el estado al cerrar v0.2. Lo decidido después (D-142…D-159, Q-15…Q-20 resueltas) está en `decisiones.md` y `advances.md`; si algo de abajo difiere, mandan esos.

## Resumen
| Tema | Cambio | Dónde quedó |
|---|---|---|
| Decisiones | D-101…D-128 aceptadas (D-121 a confirmar); nuevas D-129…D-141; preguntas Q-15…Q-21 | `decisiones.md` |
| Opciones/modificadores | Entran al MVP (Fase 3) | RN-OPT, modelo de datos, endpoints, CL-170…178, AC |
| Propina libre | Adjunta al pago; fuera del saldo | RN-TIP, pagos, cuenta, CL-180…187 |
| Descuentos y cortesías | Importe congelado, límite por rol, anulables | RN-ADJ, permisos, CU-37/38, CL-190…198 |
| Idiomas | Web cliente es-AR + en | RN-I18N, CU-47, CL-210…213 |
| Imágenes | Carpeta + puerto | RN-IMG, nube.md |
| Hosting | Web en Vercel; **backend sin host definido en ese momento (Q-15, resuelta luego con D-156)** | nube.md |
| Llamar al mozo | Incluido (adelantable) | RN-WCL, CU-62, CL-200…204 |
| Multi-sucursal simulada | Aislamiento + 2 restaurantes en pruebas | RN-TEN, WP-BE-46 |
| Contrato | `balance = charges − adjustments − paid` | CHANGELOG 0.2.0 |

## Archivos tocados
`00-gobierno/decisiones.md` · `01-producto/{actores-y-roles,alcance,glosario}.md` · `02-dominio/{reglas-negocio,modelo-dominio,estados-y-transiciones,catalogo-errores,casos-de-uso,casos-limite,modelo-datos}.md` · `04-api/{endpoints,eventos,CHANGELOG}.md` · `05-diseno/{sistema-visual,mapa-pantallas}.md` · `06-pruebas/criterios-aceptacion.md` · `07-despliegue/nube.md` · `08-agentes/*` · `design/img/README.md`.

## Precedencia
Donde una sección "v0.2" contradiga una anterior, **gana la v0.2**. En la próxima edición completa se integrarán en el cuerpo principal.
