# Entorno local (Windows + Git)

> Objetivo: que cualquier persona clone el repo y levante las dependencias locales con pocos comandos. Las versiones verificadas quedan fijadas en `infra/docker-compose.yml`.

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
| PostgreSQL | 5432 | Base local (volumen persistente `postgres-data`) |
| Mailpit | 8025 (UI) / 1025 (SMTP) | Buzón de desarrollo |
| Prism | 4010 | Mock desde `openapi.yaml` (para Web/Android sin backend) |
| Backend | 8080 | Esqueleto Spring Boot y `/version`, ejecutados desde Gradle; no está en Compose |
| Web (Vite) | 5173 | Cliente/admin cuando se implemente WP-WEB-20; hoy no está en Compose |

## 3. Primer arranque (esquema)
```bash
git clone <repo> && cd somphie-zurich   # Clona el monorepo y entra a su raíz.
if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; fi # Crea variables locales sin sobrescribir las existentes.
cd infra && docker compose up -d        # Inicia PostgreSQL, Mailpit y Prism desde su carpeta.
```

En Windows PowerShell, el equivalente es `Copy-Item .\infra\.env.example .\infra\.env`, luego `Set-Location .\infra` y `docker compose up -d`. Para apagar el entorno sin borrar la base, usar `docker compose down` desde `infra/`. El proceso Gradle se ejecuta en el host y no carga automáticamente `infra/.env`; los valores predeterminados de `application.yml` coinciden con `.env.example`, y los personalizados deben exportarse en la terminal o el IDE. Con PostgreSQL arriba, iniciá el backend desde la raíz con `.\backend\gradlew.bat -p backend bootRun`; Flyway aplica las migraciones al iniciar, `/version` devuelve build y versión del contrato, y `/actuator/health` confirma salud. Para consumirlo desde la web, `CORS_ALLOWED_ORIGINS` restringe los orígenes habilitados (por defecto `http://localhost:5173`) y debe incluir el origen exacto del navegador.

## 4. Variables de entorno (`.env.example`)
| Variable | Ejemplo | Notas |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`, `test`, `demo`, `prod` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/somphie` | Contraseña local de ejemplo |
| `JWT_SECRET` | *(generar en Base64)* | ≥ 256 bits; obligatorio para emitir/validar JWT; **nunca** versionar el real |
| `DEMO_STAFF_PASSWORD` | `demo-only-password` | Solo perfiles `dev`/`demo`; mínimo 10 caracteres |
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

Contraseña de los usuarios demo: `DEMO_STAFF_PASSWORD` (por defecto `demo-only-password`, la misma del ejemplo OpenAPI; **solo demo**, nunca reutilizar). Menú de ejemplo (≈ 12–15 productos de café, pastelería y cocina ligera) y 8 mesas con `tableCode` fijos para poder generar QR de prueba.

## 6. Comandos de calidad
| Acción | Comando |
|---|---|
| Backend: tests | `.\backend\gradlew.bat -p backend test` (desde la raíz, PowerShell) |
| Backend: tests, Flyway, Testcontainers PostgreSQL y ArchUnit | `.\backend\gradlew.bat -p backend check` (desde la raíz, PowerShell) |
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
