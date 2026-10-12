# AGENTS.md — Reglas universales (leer antes de tocar cualquier cosa)

Este archivo aplica a **todo agente o colaborador** (backend, web, android, UX, QA, DevOps, arquitecto) del proyecto **Somphie Zúrich**.

> **Antes de empezar, lee `docs/00-gobierno/advances.md`**: dice en qué estado está el proyecto, qué se decidió recientemente, qué paso del plan está en curso y qué archivos se están tocando. Si vas a cambiar algo, anota ahí lo que haces (qué, por qué, qué estudiar).

## 1. Jerarquía de la verdad (si dos documentos se contradicen, gana el de arriba)

1. `docs/00-gobierno/decisiones.md` y `adr/` — decisiones tomadas.
2. `docs/02-dominio/estados-y-transiciones.md` y `reglas-negocio.md` — reglas del negocio.
3. `docs/04-api/openapi.yaml` — contrato (fuente de verdad desde la v0.2.x; se cambia siempre junto con `CHANGELOG.md`). `endpoints.md` es el borrador previo del que salió: si difieren, manda el YAML.
4. `docs/02-dominio/catalogo-errores.md` — códigos de error.
5. La guía de tu agente en `docs/08-agentes/`.
6. El código existente. **El código nunca es la fuente de verdad de una regla**: si el código dice algo distinto, es un bug o un cambio de reglas pendiente de documentar.

## 2. Reglas de oro

1. **El backend decide.** Web y Android nunca calculan precios finales, estados, permisos ni saldos como verdad; solo los *muestran* (pueden estimar para la UI, pero lo que cuenta es la respuesta del servidor).
2. **No inventes** nombres de estados, códigos de error, campos o endpoints. Si falta algo, abre una consulta en `decisiones.md` (sección *Consultas abiertas*) y trabaja en otra cosa.
3. **Ocultar un botón no es autorizar.** Todo permiso se valida en el backend por acción **y** por recurso.
4. **Dinero = enteros en centavos** (`...Minor`). Nunca `float`/`double`.
5. **Toda operación que cambia estado** es transaccional, valida el estado actual y es idempotente si puede reintentarse (ver `03-arquitectura/idempotencia-concurrencia.md`).
6. **Cambios pequeños y revisables.** Un caso de uso por vez. Explica el *porqué* de lo que haces: el dueño está aprendiendo.
7. **Sin librerías nuevas sin justificarlas** (qué problema resuelven, alternativa descartada). Si es significativa, se crea un ADR.
8. **Sin secretos en Git.** Nunca `.env` real, tokens, claves ni contraseñas. Solo `.env.example`.
9. **Sin sobreingeniería.** Una abstracción debe justificar su existencia (aislar un detalle externo, permitir una prueba, proteger una regla). No crear interfaz+impl para todo.
10. **Todo estado de UI** tiene diseño y manejo para: cargando, error, vacío, éxito, sin conexión.

## 3. Idioma y nombres

- Código, API (JSON), base de datos, commits: **inglés**.
- Documentación, textos de interfaz, mensajes al usuario: **español rioplatense (es-AR)**.
- Los textos de UI **no se escriben dentro de componentes**: van en un archivo de textos por app (ver guía de cada agente).
- Equivalencias español ↔ inglés: `01-producto/glosario.md`.

## 4. Git (Windows)

- Archivo `.gitattributes` con `* text=auto eol=lf`. Evita el caos de saltos de línea CRLF/LF.
- Rama `main` siempre verde. Trabajo en ramas `feat/…`, `fix/…`, `docs/…`; integración mediante PR (aunque te lo revises tú mismo: el PR es tu revisión de diff).
- **Conventional Commits** con scope: `feat(backend): add order acceptance use case`, `docs(domain): clarify item undo window`, `test(web): cover cart totals`.
- Un commit = un cambio coherente. Prohibido mezclar refactor + feature.

## 5. Definición de "terminado" (DoD) — vale para cualquier funcionalidad

- [ ] Cumple las reglas `RN-*` y los criterios `AC-*` relacionados.
- [ ] Persiste datos cuando corresponde y sobrevive a un reinicio.
- [ ] Valida permisos en backend (acción + recurso).
- [ ] Maneja cargando / error / vacío / éxito / sin conexión.
- [ ] No duplica efectos ante reintentos (idempotencia).
- [ ] Tiene pruebas relevantes que **fallan** si se rompe la regla.
- [ ] Contrato (OpenAPI/errores) actualizado si cambió algo observable.
- [ ] Documentación mínima actualizada (docs o README del módulo).
- [ ] Integra con los flujos vecinos (se probó el flujo completo, no solo la pantalla).
- [ ] UI utilizable y consistente con el sistema visual.

## 6. Qué hacer cuando estás bloqueado por otro agente

1. Revisa `docs/08-agentes/dependencias.md`: ¿hay un *mock* o *fallback* definido? Úsalo.
2. Si no, registra la consulta con el formato de `decisiones.md` y avanza con otra tarea del roadmap.
3. **Nunca** resuelvas el bloqueo cambiando el contrato por tu cuenta.

## 7. Cosas que NO se hacen

- Procesar pagos reales o guardar datos de tarjeta.
- Microservicios, colas externas (Kafka/RabbitMQ), caches distribuidos: fuera de alcance.
- Delivery en el MVP.
- Animaciones o pantallas "para que se vea grande".
- Aceptar código generado sin entenderlo o sin prueba.
