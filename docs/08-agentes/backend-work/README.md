# Guía breve para agentes de backend

## Prompt reutilizable

> Ejecutá únicamente **[ID]**, ficha [ruta/enlace]. Leé primero esa ficha y solo sus lecturas obligatorias. Verificá Git, que el trabajo siga pendiente y los documentos fuente citados. Preservá cambios existentes; no amplíes el alcance ni inventes reglas, errores, endpoints o decisiones. Si hay bloqueo, registralo en `decisiones.md` cuando corresponda y en `advances.md`; avanzá solo con trabajo independiente. Implementá y probá según la ficha. No hagas commit ni push salvo autorización expresa. Entregá archivos, comandos/resultados reales, decisiones/bloqueos, estado Git y siguiente tarea habilitada.

## Antes de tocar código

1. `git status --short --branch`, rama y HEAD; leer cambios existentes en archivos que se tocarían.
2. Comprobar estado actual contra la ficha y fuente oficial. La ficha no reemplaza OpenAPI ni las reglas.
3. Leer solo la ficha, `AGENTS.md`, `backend/AGENTS.md`, la bitácora y las fuentes obligatorias de esa ficha; condicionales solo si aplica.
4. Registrar paso 🔄 en [`advances.md`](../../00-gobierno/advances.md) con objetivo y archivos previstos. Si falta definición contractual, no implementar esa parte.
5. Ejecutar en orden: comprobar evidencia actual → resolver bloqueos previos → cambiar solo alcance → pruebas que fallen si se viola la regla → documentación/bitácora.

## Convenciones de las fichas

- Cada subtítulo con ID es una ficha asignable; su primera viñeta da estado, prioridad y tipo. Prioridades: P0 decisión bloqueante, P1 cierre inmediato de BE-50, P2 dependencias siguientes de F5–F7, P3 trabajo posterior de F6–F7, P4 F8, P5 puerta de validación.
- “Leer” son las fuentes obligatorias; lo demás se consulta solo si el trabajo lo requiere. Los alcances “fuera” siguen las reglas de `AGENTS.md` y las fuentes superiores.
- La entrega mínima de abajo aplica a todas las fichas. Registrar cada comando realmente ejecutado y no inferir validación por existencia de código.

## Entrega mínima

- Qué cambió y por qué; archivos y migraciones nuevas.
- Pruebas con comando y resultado exactos; indicar lo no ejecutado y el motivo. “Compila” no significa integración validada.
- Estado de cobertura frente al umbral vigente si `check` pudo calcularse.
- Contrato/decisiones actualizados si fueron aprobados; consultas aún abiertas.
- Git al entregar, próxima ficha desbloqueada y conceptos para estudiar.
- Actualizar estado de la ficha/índice y bitácora sin copiar párrafos largos entre documentos. No marcar paquete cerrado si queda cualquier criterio obligatorio pendiente.

## Comandos existentes (usar desde raíz, solo cuando corresponda)

- Backend: `.\backend\gradlew.bat -p backend check`
- Informe JaCoCo: `.\backend\gradlew.bat -p backend jacocoTestReport`
- Contrato OpenAPI (comando fijado por CI): `npx --yes @redocly/cli@2.62.1 lint docs/04-api/openapi.yaml`

La prueba de integración depende de Docker/Testcontainers; si no arranca, registrar la falla, no desactivar Testcontainers. No correr la suite global solo por Markdown.
