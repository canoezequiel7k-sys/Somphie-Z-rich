# Diagramas de secuencia de los flujos principales

## S1 — QR → abrir sesión

```mermaid
sequenceDiagram
  actor Cl as Cliente
  participant W as Web cliente
  participant API as Backend
  participant DB as PostgreSQL
  Cl->>W: escanea QR (/t/tableCode)
  W->>API: GET /public/tables/{tableCode}
  API-->>W: {tableLabel, restaurantOpen, hasActiveSession}
  alt sin sesión activa
    Cl->>W: ingresa nombre
    W->>API: POST /public/tables/{tableCode}/sessions (Idempotency-Key)
    API->>DB: INSERT table_session OPEN (índice único parcial)
    alt carrera perdida
      API-->>W: 409 SESSION_ALREADY_OPEN
      W->>W: ir a "unirse con código"
    else ok
      API->>DB: INSERT participant HOST + hash(token)
      API-->>W: 201 {session, participant, guestToken, joinCode}
    end
  else sesión activa
    Cl->>W: nombre + código
    W->>API: POST .../join {displayName, joinCode}
    API-->>W: 201 {participant, guestToken} o error de código
  end
```

## S2 — Enviar pedido

```mermaid
sequenceDiagram
  actor Cl as Cliente
  participant W as Web cliente
  participant API as Backend
  participant DB as PostgreSQL
  participant RT as SSE
  participant S as App del personal
  Cl->>W: Confirmar pedido
  W->>W: genera Idempotency-Key (una vez por intento)
  W->>API: POST /sessions/current/orders {items, acknowledgedTotalMinor}
  API->>DB: BEGIN
  API->>DB: idempotencia · lock sesión FOR SHARE
  API->>DB: validar productos y límites · calcular precios
  alt total no coincide
    API-->>W: 409 PRICE_CHANGED {precios actuales}
  else válido
    API->>DB: INSERT pedido + ítems (snapshot) + auditoría
    API->>DB: COMMIT
    API-->>W: 201 {order #042 PENDING_REVIEW}
    API->>RT: EV-order.created
    RT-->>S: pista (nuevo pedido)
    S->>API: GET /staff/orders?status=PENDING_REVIEW
  end
```

## S3 — Aceptar y preparar

```mermaid
sequenceDiagram
  actor M as Mozo
  actor B as Barra
  actor K as Cocina
  participant AND as App Android
  participant API as Backend
  participant W as Web cliente
  M->>AND: Aceptar pedido #042
  AND->>API: POST /staff/orders/{id}/accept
  API-->>AND: 200 ACCEPTED
  API-->>W: EV-order.updated (cliente ve "Aceptado")
  B->>API: item bebida: start → ready
  K->>API: item comida: start
  API->>API: recalcula Order.status = IN_PREPARATION
  K->>API: item comida: ready
  API->>API: todos listos → Order.status = READY
  M->>API: deliver (ítems)
  API->>API: todos entregados → DELIVERED
  API-->>W: EV-order.updated
```

## S4 — Pago simultáneo (cuenta compartida)

```mermaid
sequenceDiagram
  actor A as Participante A
  actor B as Participante B
  participant API as Backend
  participant DB as PostgreSQL
  A->>API: POST payments 1500 (Key=KA)
  B->>API: POST payments 1500 (Key=KB)
  API->>DB: tx A: lock sesión FOR UPDATE
  API->>DB: tx B: espera lock
  API->>DB: A: saldo 2000 → pago 1500 → saldo 500 · COMMIT
  API-->>A: 201 {payment, bill saldo 500}
  DB-->>API: tx B obtiene lock
  API->>DB: B: saldo 500 < 1500
  API-->>B: 409 PAYMENT_EXCEEDS_BALANCE {balanceMinor: 500}
  B->>API: POST payments 500 (nueva Key)
  API-->>B: 201 saldo 0
```

## S5 — Cierre de sesión

```mermaid
sequenceDiagram
  actor M as Mozo
  participant AND as App Android
  participant API as Backend
  participant DB as PostgreSQL
  participant W as Web cliente
  M->>AND: Cerrar sesión
  AND->>API: POST /staff/sessions/{id}/close
  API->>DB: lock sesión FOR UPDATE
  API->>DB: pendientes? ítems activos? saldo?
  alt hay impedimentos
    API-->>AND: 409 SESSION_HAS_* / SESSION_BALANCE_PENDING (detalle)
  else todo en orden
    API->>DB: status=CLOSED, closeReason=SETTLED, invalida joinCode, auditoría
    API-->>AND: 200 CLOSED
    API-->>W: EV-session.closed
    W->>W: muestra "Visita finalizada" + comprobante
  end
```

## S6 — Reconexión y eventos duplicados

```mermaid
sequenceDiagram
  participant C as Cliente (Web/Android)
  participant API as Backend
  C->>API: GET /events/stream (Last-Event-ID: 120)
  API-->>C: eventos 121…130 (pueden repetirse)
  Note over C: deduplica por eventId, compara version
  API--xC: se corta la conexión
  C->>C: polling cada 5–10 s mientras no hay stream
  C->>API: GET estado completo (fuente de verdad)
  C->>API: GET /events/stream (Last-Event-ID: 130)
  alt id demasiado viejo
    API-->>C: 410 EVENT_ID_TOO_OLD
    C->>API: refresco completo
  end
```

## S7 — Autenticación del personal (refresh rotativo)

```mermaid
sequenceDiagram
  participant AND as App Android
  participant API as Backend
  AND->>API: POST /auth/login
  API-->>AND: access (15 min) + refresh (rotativo)
  AND->>API: request con access vencido
  API-->>AND: 401 TOKEN_EXPIRED
  AND->>API: POST /auth/refresh (refresh R1)
  API-->>AND: access nuevo + refresh R2 (R1 invalidado)
  Note over AND,API: si R1 se reutiliza → 401 REFRESH_TOKEN_REUSED y se revoca la familia
```
