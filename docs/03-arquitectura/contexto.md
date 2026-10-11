# Arquitectura: contexto y contenedores

## 1. Contexto (C4 nivel 1)

```mermaid
flowchart TB
  cliente([Cliente<br/>navegador móvil])
  personal([Personal<br/>app Android])
  dueno([Dueño / supervisor<br/>navegador])
  sistema[["Somphie Zúrich<br/>Sistema de gestión de cafetería"]]
  mail[(Servicio de email)]
  cliente -- "escanea QR, pide, paga (simulado)" --> sistema
  personal -- "acepta, prepara, entrega, cierra" --> sistema
  dueno -- "administra catálogo, usuarios, reportes" --> sistema
  sistema -- "comprobantes y verificación" --> mail
```

## 2. Contenedores (C4 nivel 2)

```mermaid
flowchart LR
  subgraph Navegador
    WEB[Web cliente<br/>React + TS]
    ADM[Panel admin<br/>React + TS /admin]
  end
  subgraph Movil[Dispositivo Android]
    AND[App personal<br/>Kotlin + Compose]
  end
  subgraph Nube
    API[Backend monolito modular<br/>Spring Boot + Kotlin]
    DB[(PostgreSQL)]
    STATIC[Hosting estático<br/>del front]
  end
  MAIL[Proveedor email]
  WEB -- HTTPS REST + SSE --> API
  ADM -- HTTPS REST + SSE --> API
  AND -- HTTPS REST + SSE --> API
  API --> DB
  API -. SMTP/API .-> MAIL
  STATIC --> WEB
  STATIC --> ADM
```

## 3. Responsabilidades por contenedor

| Contenedor | Decide | Muestra | Nunca |
|---|---|---|---|
| **Backend** | Reglas, permisos, precios, estados, saldos, idempotencia, auditoría | — | Confiar en datos de cliente |
| **Web cliente** | Nada de negocio | Menú, carrito, estado, cuenta, pago | Calcular precio final como verdad; guardar datos de tarjeta |
| **Panel admin** | Nada de negocio | Tablas, formularios, reportes | Aplicar permisos "solo visuales" |
| **App Android** | Nada de negocio | Colas, tablero, acciones según permisos | Marcar estados localmente sin confirmación del servidor |
| **PostgreSQL** | Consistencia final (constraints, índices únicos) | — | Contener reglas que no estén también en el dominio |

## 4. Límites de confianza

```mermaid
flowchart LR
  subgraph NoConfiable[Zona no confiable]
    B1[Navegador]
    B2[App Android]
    B3[Cualquier cliente HTTP]
  end
  subgraph Frontera[Frontera de confianza]
    G[Filtros: CORS, rate limit, auth, validación]
  end
  subgraph Confiable[Zona confiable]
    C[Casos de uso + dominio]
    D[(PostgreSQL)]
  end
  B1 --> G
  B2 --> G
  B3 --> G
  G --> C --> D
```

Todo lo que entra por la frontera se **valida de nuevo**: formato, permisos, alcance, estado y reglas.

## 5. Vista de despliegue (objetivo, 🔴 proveedor pendiente Q-05)

```mermaid
flowchart LR
  subgraph Local[Desarrollo local - Docker Compose]
    LAPI[Backend :8080]
    LDB[(PostgreSQL :5432)]
    LMAIL[Mailpit :8025]
    LPRISM[Prism mock :4010]
    LWEB[Vite :5173]
  end
  subgraph Cloud[Demo en la nube - presupuesto cero]
    CWEB[Hosting estático]
    CAPI[Backend contenedor]
    CDB[(PostgreSQL gestionado)]
  end
  GH[GitHub Actions] --> CWEB
  GH --> CAPI
```

## 6. Stack por capa

| Capa | Tecnología | Motivo |
|---|---|---|
| App personal | Kotlin + Jetpack Compose | Experiencia previa, Android moderno |
| Web cliente y admin | React + TypeScript + Vite | Tipado, un solo proyecto |
| Backend | Spring Boot + Kotlin | API estructurada, validación, seguridad |
| Base | PostgreSQL | Transacciones, restricciones, índices parciales |
| Persistencia | Spring Data JPA (+ consultas SQL/JPQL donde convenga) | Menos trabajo repetitivo |
| Migraciones | Flyway | Esquema versionado y reproducible |
| Seguridad | Spring Security | Autenticación y autorización |
| Validación | Bean Validation | Entradas validadas antes de reglas |
| Contrato | OpenAPI 3.1 | Fuente compartida |
| Tiempo real | SSE | ADR-0006 |
| Pruebas | JUnit, Testcontainers, Vitest/RTL/MSW, Playwright, Compose UI Test | Ver `06-pruebas` |

> Las versiones concretas se fijan al iniciar la Fase 2 (verificar versiones estables vigentes).
