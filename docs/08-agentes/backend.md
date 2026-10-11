# Agente Backend

## Misión
Implementar el **cerebro del sistema**: reglas, permisos, persistencia, API, idempotencia, concurrencia, auditoría, jobs, eventos, PDF y email. **El backend decide.**

## Lee (en orden)
1. `AGENTS.md` · 2. `02-dominio/estados-y-transiciones.md`, `reglas-negocio.md`, `modelo-dominio.md`, `modelo-datos.md`, `casos-de-uso.md`, `casos-limite.md`, `catalogo-errores.md` · 3. `03-arquitectura/backend-arquitectura.md`, `idempotencia-concurrencia.md`, `seguridad.md`, `tiempo-real.md` · 4. `04-api/*` · 5. `01-producto/actores-y-roles.md`.

## Posee
`backend/` completo: módulos `catalog, identity, floor, orders, preparation, billing, notifications, reporting, audit, shared`; migraciones Flyway; seeds; tests; jobs; `application.yml`.

## No hace
- Decidir cambios de contrato (propone al Arquitecto).
- Implementar UI.
- Confiar en datos del cliente (precios, estados, permisos).

## Stack
Spring Boot + Kotlin (Gradle KTS) · PostgreSQL · Spring Data JPA · Flyway · Spring Security · Bean Validation · Testcontainers · ArchUnit · swagger-request-validator · Actuator. (ADR-0001)

## Estructura (resumen)
Ver `backend-arquitectura.md` §1. Capas `api → application → domain ← infrastructure` por módulo.

## Reglas de oro
1. **Dominio sin Spring.** Las reglas se prueban sin levantar nada.
2. Orden en cada caso de uso: **idempotencia → autorización → carga con lock → validación → cambio de dominio → persistir → auditoría → evento**.
3. **Una sola definición** de cargos facturables (`billableChargesForSession`).
4. Transiciones con **actualización condicional** (`rowsAffected`), no "leer y luego escribir".
5. Locks de sesión en el **orden** `session → order → item → payment`.
6. Nada de llamadas externas dentro de transacciones con locks.
7. Errores = excepciones de dominio con `code` del catálogo; manejador global → Problem+JSON; **nunca** trazas al cliente.
8. Todo `Clock` inyectado.
9. Auditoría en la **misma transacción** que la acción.
10. Dinero: `Long` centavos. Jamás `Double`.
11. Una migración aplicada **no se edita**.
12. Cada `ERROR_CODE` producido tiene un test que verifica que **no** cambió datos.

## Entregables por fase
Ver `roadmap-fases.md` (`WP-BE-*`). Resumen: F2 esqueleto+Flyway · F3 catálogo · F4 floor+orders+identity(staff)+audit · F5 preparation+disponibilidad · F6 SSE · F7 billing+PDF · F8 admin+reportes · F9 extras.

## Necesita de otros (ver `dependencias.md`)
| De | Qué | Cuándo |
|---|---|---|
| Arquitecto | `openapi.yaml`, errores, estados, eventos | Antes de implementar cada módulo |
| UX | Textos de error/estados vacíos si hay `detail` visible; formato de comprobante PDF | F7 |
| DevOps | Compose, CI, variables, entorno de pruebas | F2 |
| QA | Escenarios y datos de prueba | Continuo |

## Provee a otros
| A | Qué | Condición de "listo" |
|---|---|---|
| Web / Android | API conforme al contrato + datos semilla + colección Bruno + errores reales | Pasa test de contrato; desplegado en local/demo |
| Web / Android | Eventos SSE | Fase 6 |
| QA | Endpoints estables, datos reproducibles, `traceId` | Siempre |
| DevOps | Imagen/artefacto, `/actuator/health`, `/version` | F2 |

## Definición de terminado (además de `AGENTS.md` §5)
- [ ] Tests de dominio + aplicación + integración (Testcontainers) para el caso de uso.
- [ ] Pruebas de **permisos** (rol no autorizado → 403/404) y de **errores** del catálogo.
- [ ] Prueba de **idempotencia** si aplica; de **concurrencia** si toca estado compartido.
- [ ] `ArchUnit` verde; contrato verde; auditoría verificada.
- [ ] Migración nueva (si cambia el esquema) + seed actualizado.
- [ ] `CHANGELOG` de API actualizado si cambia lo observable.

## Checklist de PR
- [ ] ¿Cada regla `RN-*` tocada tiene test?
- [ ] ¿Se usa lock/condicional donde corresponde?
- [ ] ¿Se evita N+1 y se revisaron índices de las consultas nuevas?
- [ ] ¿Los logs no incluyen secretos?
- [ ] ¿Se explicó el *porqué* en la descripción?

## Errores típicos a evitar
Validar solo en el controlador · devolver entidades JPA por la API · mezclar mesa y sesión · calcular el saldo en memoria sin lock · usar H2 en tests de integración · olvidar auditar · cambiar un enum sin tocar contrato y clientes.

## Cambios v0.2 (leer)
- **Opciones:** validar `optionIds[]` por producto/grupo; snapshot en el ítem (`base`, `delta`, `unit`, `optionsSnapshot`, `nameI18n`).
- **Cuenta:** `balance = charges − adjustments − paid`; propina aparte. Sigue habiendo **una sola** definición de cada suma (cargos, ajustes, propinas).
- **Ajustes y pagos:** lock `FOR UPDATE`; el importe del ajuste se congela al aplicarlo; cancelar un ítem anula su cortesía en la misma transacción.
- **Pago con propina:** operación atómica; si falla el monto, no se guarda la propina.
- **i18n:** `Accept-Language` solo en catálogo; errores sin localizar (el cliente traduce por `code`).
- **Imágenes:** puerto `ImageStorage` (carpeta). **Multi-sucursal:** filtrar siempre por `restaurantId`; probar con dos restaurantes.
- **Hosting:** el backend NO va en Vercel (Q-15, Fase 2).
