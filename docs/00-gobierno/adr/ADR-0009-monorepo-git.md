# ADR-0009: Monorepo y flujo Git
**Estado:** propuesta (D-120)

## Decisión
Un repositorio (`somphie-zurich`) con `backend/`, `web/`, `android/`, `docs/`, `infra/`. Beneficio: un PR puede tocar contrato + backend + clientes de forma coherente; documentación junto al código. CI con filtros por ruta.

## Consecuencias
Historial único; ramas cortas; Conventional Commits con scope por carpeta.

---
**ADRs pendientes de redactar en su fase:** pagos simulados (F7), email y PDF (F7/F9), estrategia de despliegue (F2/F10), almacenamiento de imágenes (F8, Q-04).
