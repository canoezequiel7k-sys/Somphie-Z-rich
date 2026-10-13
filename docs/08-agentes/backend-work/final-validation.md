# Ficha de validación final del backend (50–82)

## WP-BE-FINAL-T01 — Puerta de calidad y disponibilidad de APIs

- **Estado/prioridad/tipo:** Pendiente · P5 · cierre de fase.
- **Objetivo:** demostrar que los paquetes requeridos WP-BE-50…82 cumplen contratos, reglas, seguridad y calidad antes de declararlos consolidados. No es un sustituto de pruebas por paquete.
- **Dependencias:** cada WP-BE-50…82 cerrada con evidencia; OpenAPI/CHANGELOG sincronizados; decisiones pendientes resueltas o declaradas fuera del alcance por Arquitectura. Fase 9 queda separada; no es requisito automático.
- **Lectura obligatoria:** índice, todas las fichas marcadas completas, [`estrategia de pruebas`](../../06-pruebas/estrategia.md), [`backend.md`](../backend.md), seguridad, contratos y CI.
- **Plan:** (1) inventariar endpoints del contrato versus controllers/tests; (2) Redocly; (3) `.\backend\gradlew.bat -p backend check` con Docker/Testcontainers; (4) revisar Jacoco al umbral vigente 90 %, ArchUnit y errores omitidos; (5) revisar autorizaciones, tenant/estación, idempotencia, locks y auditoría; (6) `git diff --check`, documentación/bitácora y reporte de brechas.
- **Aceptación:** Redocly, build/check y suites pasan; Testcontainers ejecuta PostgreSQL real; cobertura global >= 90 % sin cambiar exclusiones/umbral; contrato, tests de seguridad y arquitectura verdes; ninguna brecha contractual/integridad sin registrar. Si la infraestructura impide una puerta, no declarar cierre.
- **Fuera:** no avanzar extras de Fase 9 ni modificar criterios para conseguir verde.
- **Entrega:** comandos exactos/resultados, conteo de pruebas, cobertura numerador/denominador, estado de Docker/CI, riesgos y decisión explícita “backend consolidado” solo si todas las puertas pasaron.
