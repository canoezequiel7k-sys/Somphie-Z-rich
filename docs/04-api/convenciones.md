# Convenciones de la API

> Estado: **borrador v0.1**. El contrato formal será `openapi.yaml` (WP-ARQ-10, Fase 1). Hasta entonces, este documento y `endpoints.md` son la referencia.

## 1. Generales
| Tema | Convención |
|---|---|
| Base | `/api/v1` |
| Formato | JSON UTF-8, `camelCase` |
| IDs | UUID (strings) |
| Fechas | ISO-8601 UTC con `Z` (`2026-10-11T15:03:22Z`) |
| Dinero | Entero en centavos, campos `*Minor` (`priceMinor: 245000` = $2.450,00). Moneda en `bill.currency` / settings |
| Enums | `UPPER_SNAKE_CASE`, idénticos a `estados-y-transiciones.md` |
| Nulos | Campos opcionales **se omiten** o van `null` de forma consistente por esquema; no se mezclan |
| Idioma | Campos/valores en inglés; `title`/`detail` de errores en español |
| Versionado | Cambios compatibles (campos opcionales nuevos) sin romper; incompatibles → `/api/v2` o negociación documentada |
| Paginación | Cursor: `?limit=50&cursor=…` → `{ items: [], nextCursor: string|null }` |
| Orden | `?sort=createdAt` (`-` para descendente) solo donde se documente |
| Filtros | Query params explícitos (ej. `status=PENDING_REVIEW,ACCEPTED`) |

## 2. Cabeceras
| Cabecera | Dirección | Uso |
|---|---|---|
| `Authorization: Bearer <token>` | Req | Token de invitado o JWT de cuenta |
| `Idempotency-Key: <uuid>` | Req | Obligatoria en operaciones marcadas (ver `idempotencia-concurrencia.md`) |
| `Idempotent-Replayed: true` | Res | La respuesta es una repetición guardada |
| `If-None-Match` / `ETag` | Req/Res | Menú y recursos cacheables |
| `X-Request-Id` | Req (opcional) / Res | Correlación; el servidor devuelve `traceId` en errores |
| `Retry-After` | Res | En `429`/`503` |
| `Accept-Language` | Req | Reservado (es-AR por defecto) |

## 3. Códigos HTTP
| Código | Uso |
|---|---|
| 200 | OK / repetición idempotente de operación natural |
| 201 | Creado (incluye `Location` si aplica) |
| 204 | Sin contenido (solo para borrados) |
| 304 | Sin cambios (ETag) |
| 400 | Validación / malformada |
| 401 | No autenticado / token vencido |
| 402 | **No se usa** (rechazo simulado = 422 `PAYMENT_DECLINED`) |
| 403 | Autenticado sin permiso o alcance |
| 404 | No existe o fuera de alcance |
| 409 | Conflicto de estado/concurrencia |
| 410 | Recurso fuera de retención (`EVENT_ID_TOO_OLD`) |
| 413/415 | Tamaño / tipo |
| 422 | Regla de negocio violada con datos sintácticamente válidos |
| 429 | Rate limit |
| 500/503 | Error del servidor / no disponible |

## 4. Formato de error (RFC 7807 extendido)
```json
{
  "type": "https://api.somphiezurich.example/errors/validation-failed",
  "title": "Datos inválidos",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "detail": "Revisá los campos marcados.",
  "traceId": "9f1c2a7e4b",
  "fieldErrors": [
    { "field": "items[0].quantity", "code": "MAX_EXCEEDED", "message": "Máximo 20 por producto" }
  ],
  "extra": {}
}
```
Los clientes se programan contra `code` (y `fieldErrors[].code`), **no** contra `title`/`detail`.

## 5. Autorización en la especificación
Cada endpoint declara: **Auth** (`public` | `guest` | `staff` | `owner`), **Permiso** requerido y **Alcance**. Ver `endpoints.md`.

## 6. Reglas para modificar el contrato
1. Cambiar primero `openapi.yaml` + `CHANGELOG.md`.
2. Los **ejemplos** en OpenAPI son parte del contrato (alimentan mocks de Prism y tests).
3. Backend: test de contrato falla si la respuesta no cumple el esquema.
4. No quitar ni renombrar campos/códigos sin versión nueva.
5. Campos nuevos son **opcionales** para el cliente: un cliente viejo no debe romperse.
6. Enums: los clientes deben tolerar valores desconocidos (caer en estado "desconocido" sin crash).

## 7. Mocks y herramientas
| Herramienta | Uso |
|---|---|
| **Prism** | Servidor mock desde `openapi.yaml` (`:4010`) para Web/Android sin backend |
| **Spectral** | Lint del YAML en CI |
| **openapi-typescript** | Tipos para Web |
| **Bruno** (colecciones en `docs/04-api/bruno/`) | Probar el backend a mano antes de tener UI |
| **swagger-request-validator** | Test de contrato en backend |
