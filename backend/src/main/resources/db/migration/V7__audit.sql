-- Migración V7: Módulo Audit (Registro de eventos de auditoría append-only).

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_type TEXT NOT NULL CHECK (actor_type IN ('CUSTOMER', 'STAFF', 'SYSTEM')),
    actor_id UUID NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    reason TEXT,
    before_summary JSONB,
    after_summary JSONB,
    trace_id TEXT
);

CREATE INDEX idx_audit_occurred_at ON audit_event(occurred_at);
CREATE INDEX idx_audit_target ON audit_event(target_type, target_id);
