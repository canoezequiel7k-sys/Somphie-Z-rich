-- Migración V5: Registro de idempotencia para prevenir duplicados en operaciones clave.

CREATE TABLE idempotency_record (
    scope TEXT NOT NULL,
    key VARCHAR(36) NOT NULL,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope, key)
);
