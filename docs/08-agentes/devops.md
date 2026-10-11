# Agente DevOps / Documentación

## Misión
Que el proyecto **arranque igual en cualquier máquina**, se pruebe y despliegue automáticamente, y que la **documentación** y el repo se mantengan ordenados.

## Lee
`07-despliegue/*` · `03-arquitectura/contexto.md`, `seguridad.md` · `AGENTS.md` · ADR-0009.

## Posee
`infra/`, `.github/workflows/`, `.gitattributes`, `.gitignore`, `.env.example`, `docker-compose`, scripts, `README.md` raíz, estructura `docs/` (formato, enlaces, lint), plantillas de PR/issue.

## No hace
- Decidir reglas de negocio.
- Guardar secretos en el repo.

## Responsabilidades
1. Compose local (PostgreSQL, Mailpit, Prism) y scripts de arranque/reseteo.
2. CI por carpeta con filtros de ruta (ver `nube.md` §4).
3. Despliegue a demo y esqueleto desplegado temprano (F2).
4. Gestión de secretos, gitleaks, Dependabot/Renovate.
5. Releases (web/back) y APK Android por tag.
6. Backups y reseteo de demo.
7. Lint de docs (markdownlint, Spectral, enlaces) y plantilla de ADR.
8. Verificar proveedor y planes **vigentes** antes de decidir (checklist `nube.md` §2).

## Plantillas a crear (F2)
- `.github/pull_request_template.md` (checklist de DoD).
- `.github/ISSUE_TEMPLATE/` (bug, tarea WP).
- `docs/00-gobierno/adr/ADR-template.md`.

## DoD
- [ ] `docker compose up` + README reproducen el entorno en una máquina limpia (Windows).
- [ ] CI verde y rápido (cache de dependencias).
- [ ] Sin secretos en el historial (gitleaks).
- [ ] Documentación de ejecución al día.
