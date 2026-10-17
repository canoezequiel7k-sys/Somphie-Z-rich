# Somphie Zürich Web

Web móvil del cliente y, en una fase posterior, panel de administración. El personal de salón, cocina y barra sigue en el alcance Android aprobado.

## Requisitos

- Node.js 24 o una versión compatible con Vite.
- Corepack y pnpm 12.
- Backend local o un mock compatible con `docs/04-api/openapi.yaml`.

## Desarrollo

```powershell
corepack pnpm install
Copy-Item .env.example .env.local
corepack pnpm dev
```

La URL predeterminada del backend es `http://localhost:8080/api/v1`. Se puede reemplazar con `VITE_API_BASE_URL`; `VITE_MEDIA_BASE_URL` configura el origen de imágenes. No guardar secretos en variables `VITE_*`, porque se publican en el navegador.

El backend habilita CORS solo para los orígenes listados en `CORS_ALLOWED_ORIGINS` (separados por coma); por defecto admite `http://localhost:5173`. El navegador puede bloquear una llamada cross-origin si la URL, el origen o los encabezados preflight no están autorizados. La web no bloquea acciones basándose en `navigator.onLine`, porque ese indicador no verifica la disponibilidad de la API: intenta la solicitud y presenta el error de conexión, HTTP o formato que reciba.

El primer recorrido usa `/t/:tableCode`: resuelve el QR, carga el menú, abre una sesión libre o permite sumarse a una sesión existente. Para navegar el recorrido manualmente, usar una URL como `/t/TABLE-7` con el backend o un mock configurado.

Con una sesión abierta, el menú permite elegir opciones y armar un carrito separado por sesión. El subtotal es una estimación calculada con centavos enteros: al crear el pedido, el servidor vuelve a validar productos, opciones y precios. Si responde `PRICE_CHANGED`, la web muestra el total actual y requiere una confirmación explícita antes de enviar una operación nueva.

Los carritos sobreviven a una recarga en el almacenamiento local del navegador. Si el envío termina con una respuesta ambigua, la web guarda en `sessionStorage` el cuerpo exacto y su clave `Idempotency-Key`; el reintento repite esa misma operación para no duplicar pedidos. Los pedidos y sus estados se consultan por REST cada 8 segundos mientras la sesión permanece abierta, además de permitir una actualización manual. Las pruebas actuales usan MSW: no validan conectividad, persistencia ni concurrencia contra el backend real.

## Comandos

```powershell
corepack pnpm run api:generate
corepack pnpm run lint
corepack pnpm run typecheck
corepack pnpm run test
corepack pnpm run build
corepack pnpm run check
```

Los tipos de `src/shared/api/schema.d.ts` se generan desde `../docs/04-api/openapi.yaml`; no editarlos a mano. Las pruebas de UI usan MSW y respuestas contractuales. La API real sigue siendo necesaria para validar integración, concurrencia y persistencia.
