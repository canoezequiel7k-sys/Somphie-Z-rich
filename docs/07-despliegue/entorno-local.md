# Entorno local (Windows + Git)

> Objetivo: que cualquier persona clone el repo y levante todo con pocos comandos. Las versiones exactas se fijan en Fase 2 (verificar las estables vigentes).

## 1. Requisitos
| Herramienta | Para qué | Nota |
|---|---|---|
| Git for Windows | Control de versiones | Configurar `core.autocrlf=false` y usar `.gitattributes` (`* text=auto eol=lf`) |
| JDK LTS vigente | Backend | Instalar con Adoptium (Temurin) o Scoop |
| IntelliJ IDEA (Community o Ultimate) | Backend Kotlin | — |
| Android Studio | App Android | SDK y emulador |
| Node.js LTS + pnpm | Web | Gestor de versiones: `fnm` o `nvm-windows` |
| Docker Desktop (WSL2) | PostgreSQL, Mailpit, Prism, Testcontainers | Habilitar WSL2 |
| VS Code (opcional) | Web y docs | Extensiones: Markdown Mermaid, ESLint, Prettier |
| Bruno | Probar la API | Colecciones en el repo |

## 2. Servicios locales (`infra/docker-compose.yml`)
| Servicio | Puerto | Para qué |
|---|---|---|
| PostgreSQL | 5432 | Base de datos (volumen persistente) |
| Mailpit | 8025 (UI) / 1025 (SMTP) | Buzón de desarrollo |
| Prism | 4010 | Mock desde `openapi.yaml` (para Web/Android sin backend) |
| Backend | 8080 | API (también puede correr desde el IDE) |
| Web (Vite) | 5173 | Cliente y admin |

## 3. Primer arranque (esquema)
```bash
git clone <repo> && cd somphie-zurich
cp infra/.env.example infra/.env        # completar valores locales (NO se versiona .env)
docker compose -f infra/docker-compose.yml up -d
cd backend && ./gradlew bootRun         # perfil dev: aplica migraciones + semillas demo
cd ../web && pnpm install && pnpm dev
# Android: abrir /android en Android Studio; el emulador accede al backend en http://10.0.2.2:8080
```

## 4. Variables de entorno (`.env.example`)
| Variable | Ejemplo | Notas |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`, `test`, `demo`, `prod` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/somphie` | Contraseña local de ejemplo |
| `JWT_SECRET` | *(generar)* | ≥ 256 bits; **nunca** versionar el real |
| `WEB_BASE_URL` | `http://localhost:5173` | Para armar URLs de QR |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Lista explícita |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | Mailpit |
| `DEMO_MODE` | `true` | Habilita rechazo simulado `.13` y datos demo |
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | Web |

## 5. Datos de demostración (solo perfiles `dev`/`demo`)
| Usuario | Rol | Estaciones |
|---|---|---|
| `owner@somphie.demo` | OWNER | todas |
| `waiter@somphie.demo` | WAITER | — |
| `bar@somphie.demo` | BAR | BAR |
| `kitchen@somphie.demo` | KITCHEN | KITCHEN |

Contraseñas de demo documentadas en el README del backend (**solo demo**, nunca reutilizar). Menú de ejemplo (≈ 12–15 productos de café, pastelería y cocina ligera) y 8 mesas con `tableCode` fijos para poder generar QR de prueba.

## 6. Comandos de calidad
| Acción | Comando |
|---|---|
| Backend: tests | `./gradlew test` |
| Backend: tests + ArchUnit + contrato | `./gradlew check` |
| Web: lint/tipos/tests | `pnpm lint && pnpm typecheck && pnpm test` |
| Web: E2E | `pnpm e2e` |
| Android: unit | `./gradlew testDebugUnitTest` |
| Android: UI | `./gradlew connectedDebugAndroidTest` |
| Contrato | `npx @redocly/cli lint docs/04-api/openapi.yaml` |

## 7. Problemas frecuentes en Windows
| Síntoma | Causa | Solución |
|---|---|---|
| Archivos con CRLF en diffs | Autoconversión de Git | `.gitattributes` + `core.autocrlf=false` |
| Testcontainers no arranca | Docker Desktop apagado / WSL2 | Iniciar Docker; revisar WSL2 |
| Rutas largas fallan | Límite de Windows | Habilitar rutas largas en Git (`core.longpaths=true`) |
| Android no ve el backend | `localhost` apunta al emulador | Usar `10.0.2.2` (emulador) o IP de la LAN (dispositivo físico) |
| Puerto ocupado | Otro servicio | Cambiar puerto en `.env`/compose |
