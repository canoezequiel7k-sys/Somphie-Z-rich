# Nube, CI/CD y operación (presupuesto cero)

> No se puede prometer que una solución de producción sea gratuita para siempre. Por eso: **modo local reproducible siempre**, y la nube es la demo. Proveedor y planes se **verifican al decidir** (Q-05); aquí se define qué comprobar, no qué elegir.

## 1. Entornos
| Entorno | Propósito | Datos | Secretos |
|---|---|---|---|
| Local | Desarrollo diario | Semillas demo | `.env` local (no versionado) |
| Pruebas (CI) | Integración/E2E efímeros | Contenedores descartables | Secretos de CI |
| Demo (nube) | Versión pública | Ficticios controlados | Gestor del proveedor |

## 2. Checklist para elegir proveedor (verificar en cada candidato)
- [ ] Plan gratuito: límites de CPU/RAM/horas/tráfico.
- [ ] ¿Se suspende por inactividad? ¿cuánto tarda el arranque en frío?
- [ ] PostgreSQL: tamaño máx., conexiones, **persistencia** y si **expira**.
- [ ] Soporte de conexiones largas (SSE) y tiempo máx. de inactividad.
- [ ] HTTPS y dominios personalizados.
- [ ] Variables secretas.
- [ ] Backups y recuperación.
- [ ] Logs y métricas accesibles.
- [ ] Qué ocurre si se excede el plan (corte vs cobro).
- [ ] Región (latencia desde Argentina).
- [ ] Almacenamiento de imágenes (Q-04).

| Pieza | Candidatos a evaluar (sin compromiso) |
|---|---|
| Front estático | Cloudflare Pages, Vercel, Netlify, GitHub Pages |
| Backend (contenedor) | Servicios PaaS con capa gratuita, Cloud Run, una VM gratuita |
| PostgreSQL | Proveedores gestionados con capa gratuita |
| Email | Proveedores transaccionales con capa gratuita (verificar dominio y límites) |

## 3. Estrategia de despliegue temprano
1. **Fase 2 — esqueleto caminante:** desplegar `GET /version`, `GET /menu` mínimo y la web consumiéndolo, sobre HTTPS. Objetivo: aprender configuración real (variables, CORS, HTTPS, conexión a base).
2. Cada fase siguiente despliega a **demo** al pasar CI en `main`.
3. Fase 10: congelar una versión de demo + datos + README.

## 4. CI/CD (GitHub Actions)
| Workflow | Disparador (filtros de ruta) | Pasos |
|---|---|---|
| `backend.yml` | `backend/**`, `docs/04-api/**` | JDK → build → tests (Testcontainers) → ArchUnit → contrato → artefacto/imagen |
| `web.yml` | `web/**`, `docs/04-api/**` | pnpm → lint → typecheck → vitest → build → (E2E en `main`) |
| `android.yml` | `android/**` | assembleDebug → unit tests → lint |
| `docs.yml` | `docs/**` | markdownlint + Redocly CLI (lint del OpenAPI) + chequeo de enlaces |
| `security.yml` | siempre | gitleaks + auditoría de dependencias (informativa) |
| `deploy-demo.yml` | push a `main` verde | Desplegar front y backend a demo |
| `release-android.yml` | tag `android-v*` | Compilar APK firmado y publicarlo en GitHub Releases (D-122) |

Firma de la APK: keystore **fuera** del repo (secreto de GitHub, cifrado).

## 5. Observabilidad mínima
- Logs JSON con `traceId`.
- `/actuator/health` (liveness/readiness) para el proveedor.
- Métricas básicas (peticiones, errores, latencia, conexiones SSE).
- Alerta simple: un *ping* programado al health (también mitiga el "sueño" de planes gratuitos, respetando sus condiciones de uso).

## 6. Respaldo y recuperación
| Aspecto | Decisión |
|---|---|
| Backup | Verificar backups del proveedor; añadir `pg_dump` programado como respaldo extra |
| Prueba de restauración | Documentada y ejecutada al menos una vez (CL-165) |
| Migraciones | Hacia adelante (Flyway); un rollback = nueva migración o restaurar backup |
| Reinicio de demo | Script de **reseteo** de datos de demo |

## 7. Modo demo
- `DEMO_MODE=true`: semillas, usuarios demo, rechazo simulado `.13`.
- Banner visible "Entorno de demostración — pagos simulados".
- Datos ficticios; sin datos reales de personas.

## 8. Proceso de release
1. PR → CI verde → merge a `main`.
2. Deploy automático a demo.
3. Etiquetar versión (`vX.Y.Z`); notas de versión (qué cambió, cambios de contrato).
4. Android: etiqueta `android-v…` → APK en Releases.
5. Rollback: redeploy de la versión anterior; si hubo migración incompatible, restaurar backup.

## 9. Gestión de costos y riesgos
| Riesgo | Mitigación |
|---|---|
| Plan gratuito cambia o se agota | Modo local reproducible; documentar límites; evitar dependencias exclusivas de un proveedor (puertos/adaptadores) |
| Arranque en frío lento | Mensaje en UI (SC-C-14), health ping, optimizar arranque |
| Base gratuita expira/pausa | Backups; script de reconstrucción desde migraciones + semillas |
| Servicios de email con límites | Adaptador intercambiable; Mailpit en dev |

## 10. Decisiones v0.2 (D-132, D-133, Q-15)

| Pieza | Decisión | Consecuencias |
|---|---|---|
| **Web (cliente + admin)** | **Vercel** mientras sea gratis (D-133). Verificar términos vigentes del plan gratuito (suele ser de uso no comercial: adecuado para portfolio) | Build estático; variables `VITE_*`; CORS desde el backend con los orígenes exactos |
| **Backend + PostgreSQL** | **No en Vercel**: una app Spring Boot de larga duración con SSE no encaja en funciones serverless. Host a elegir en Fase 2 (Q-15) con el checklist de la sección 2 | Backend en otro dominio ⇒ autenticación por `Authorization: Bearer` (sin cookies); verificar si el proxy del host corta SSE |
| **Imágenes** | Carpeta (D-132): en la demo, `web/public/menu/*.webp` versionadas y servidas por el CDN del front | Subir desde admin puede no persistir en hosting efímero (Q-20) |
| **Alternativa a evaluar** | *Rewrites* de Vercel para que `/api` sea mismo origen | Probar que no corte ni bufferice SSE antes de adoptarla |
