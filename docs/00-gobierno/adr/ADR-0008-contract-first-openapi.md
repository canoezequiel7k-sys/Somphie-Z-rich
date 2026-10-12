# ADR-0008: Contrato primero con OpenAPI
**Estado:** propuesta (D-104, D-121, D-148)

## Decisión
`docs/04-api/openapi.yaml` (OpenAPI 3.1) es la fuente del contrato y se escribe **antes** de integrar clientes. Backend lo cumple con controladores escritos a mano + tests de contrato que fallan si una respuesta se desvía. Web genera tipos (`openapi-typescript`); Android genera solo modelos o los escribe a mano verificados con fixtures. **Prism** levanta un servidor mock desde el YAML para que Web y Android avancen sin backend. **Redocly CLI** valida el contrato en CI (D-148).

## Consecuencias
Los ejemplos del YAML son parte del contrato (alimentan los mocks). Todo cambio de contrato pasa por `CHANGELOG`. Redocly CLI hace lint, vista previa y empaquetado del YAML con una sola configuración (`redocly.yaml`, en la raíz); reemplaza a Spectral.
